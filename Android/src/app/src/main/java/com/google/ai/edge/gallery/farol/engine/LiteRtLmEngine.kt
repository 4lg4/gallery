/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.farol.engine

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/**
 * The channel name used by Gemma 4 to deliver incremental thinking tokens.
 *
 * Mirrored from [com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper]:
 *   `resultListener(message.toString(), false, message.channels["thought"])`
 *
 * This is the key into [Message.channels] that the LiteRT-LM runtime populates when the request
 * carries `extraContext["enable_thinking"] = "true"`.  Do NOT invent a different key — this
 * constant matches what the runtime actually delivers.
 */
private const val THOUGHT_CHANNEL_KEY = "thought"

/**
 * The extra-context key that activates Gemma 4 thinking mode.
 *
 * Mirrored from [com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel]:
 *   `val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null`
 * and passed to [com.google.ai.edge.litertlm.Conversation.sendMessageAsync].
 */
private const val EXTRA_CONTEXT_ENABLE_THINKING = "enable_thinking"

private const val TAG = "LiteRtLmEngine"

/**
 * [InferenceEngine] backed by LiteRT-LM 0.11.0.
 *
 * ## Design
 * - Creates a single [Engine] at construction time (mirroring [LlmChatModelHelper.initialize]).
 * - Per request: creates a FRESH [Conversation] (with per-request [SamplerConfig] when possible),
 *   sends the message, then closes the conversation.
 * - A [Mutex] serializes concurrent requests (single-flight) for both [generate] and
 *   [generateStream].  For streaming the mutex is held for the flow's entire lifetime.
 *
 * ## maxTokens
 * [EngineConfig.maxNumTokens] is set at engine-init time using the [initMaxTokens] constructor
 * parameter (default 4096).  Per-request [maxTokens] is forwarded here because LiteRT-LM 0.11.0's
 * [ConversationConfig] does not expose a per-conversation token cap.
 *
 * ## temperature
 * Temperature is wired per-request via [ConversationConfig]'s [SamplerConfig].  If the device
 * backend is NPU/TPU (not applicable to FAROL v1), the sampler is omitted per the helper pattern.
 *
 * ## Token counts
 * LiteRT-LM 0.11.0 does not expose token counts in [Message] or via any public API visible in
 * [LlmChatModelHelper].  Counts are ESTIMATED as (chars / 4), which is a rough GPT-style
 * approximation. Marked with "ESTIMATED" in code comments.
 *
 * @param modelPath Absolute path to the `.litertlm` model file on the device.
 * @param modelDisplayName Human-readable model name returned by [modelName].
 * @param enableVision Whether to enable the vision encoder (GPU backend).  Defaults to true since
 *   Gemma 4 E4B supports images.
 * @param initMaxTokens Engine-level token cap.  Capped at a sane default (4096) if callers do not
 *   specify.
 */
class LiteRtLmEngine(
  private val modelPath: String,
  private val modelDisplayName: String,
  private val enableVision: Boolean = true,
  private val initMaxTokens: Int = 4096,
) : InferenceEngine {

  override val modelName: String get() = modelDisplayName

  private val mutex = Mutex()
  private val engine: Engine

  init {
    // Mirror LlmChatModelHelper.initialize: prefer GPU for LLM; GPU for vision backend.
    val preferredBackend: Backend = Backend.GPU()
    val visionBackend: Backend? = if (enableVision) Backend.GPU() else null

    val engineConfig = EngineConfig(
      modelPath = modelPath,
      backend = preferredBackend,
      // must be GPU for Gemma 4 (mirrors helper comment "must be GPU for Gemma 3n")
      visionBackend = visionBackend,
      // audio not needed in FAROL v1; set to null (helper: "must be CPU for Gemma 3n")
      audioBackend = null,
      maxNumTokens = initMaxTokens,
    )
    Log.d(TAG, "Creating engine: modelPath=$modelPath, backend=$preferredBackend, " +
      "vision=$visionBackend, maxTokens=$initMaxTokens")
    engine = Engine(engineConfig)
    engine.initialize()
    Log.d(TAG, "Engine initialized.")
  }

  // ── Streaming ─────────────────────────────────────────────────────────────

  /**
   * Returns a [Flow] that emits text chunks as they arrive from [MessageCallback.onMessage].
   *
   * ## maxTokens
   * Not consumed per-request in LiteRT-LM 0.11.0 — the engine-level cap is set at init time via
   * [initMaxTokens].  The parameter is accepted for interface compatibility and future support.
   *
   * ## Mutex unlock contract
   * The [mutex] is acquired once, before [createConversation], and released exactly once via the
   * [releasedOnce] guard — on whichever of the three exit paths fires first:
   *
   *   A) [createConversation] throws  → caught by the outer try/catch; mutex released there;
   *      flow closed with the error; `return@callbackFlow` skips [awaitClose].
   *   B) [sendMessageAsync] setup throws → caught by the inner try/catch; mutex released there
   *      after closing the conversation; flow closed with the error; `return@callbackFlow` skips
   *      [awaitClose].
   *   C) Normal completion, [onError], or flow cancellation → [awaitClose] releases the mutex.
   *
   * [releasedOnce] makes the release idempotent: whichever path runs first wins; subsequent calls
   * are no-ops, so double-unlock (which would throw [IllegalStateException]) is impossible.
   */
  override fun generateStream(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
    thinking: Boolean,
  ): Flow<StreamChunk> {
    // Validate before returning the flow so IllegalArgumentException is thrown at call site,
    // not deferred to collection time — and without touching the mutex.
    require(prompt.isNotBlank() || images.isNotEmpty()) {
      "generateStream: prompt must be non-blank or at least one image must be provided"
    }
    // Producer is GPU-throttled and bounded by maxNumTokens, so unbounded buffering is safe.
    return callbackFlow {
      // Acquire the mutex for the full flow lifetime.
      mutex.lock()
      Log.d(TAG, "generateStream: acquired mutex, prompt=${prompt.take(80)}..., thinking=$thinking")

      // Guard: exactly one path may call mutex.unlock().
      val releasedOnce = AtomicBoolean(false)
      fun releaseMutex() {
        if (releasedOnce.compareAndSet(false, true)) mutex.unlock()
      }

      // Path A: createConversation itself may throw (e.g. native OOM, bad model state).
      val conversation = try {
        createConversation(temperature)
      } catch (e: Exception) {
        Log.e(TAG, "generateStream: createConversation failed", e)
        releaseMutex() // Path A unlock
        close(e)
        return@callbackFlow
      }

      // Path B: sendMessageAsync setup failure (e.g. buildContents, engine API error).
      try {
        val contents = buildContents(prompt, images)
        // Mirror LlmChatViewModel.generateResponse: pass extraContext["enable_thinking"]="true"
        // when thinking is requested.  When false, pass an empty map (no change to prior behaviour).
        val extraContext = if (thinking) mapOf(EXTRA_CONTEXT_ENABLE_THINKING to "true") else emptyMap()
        conversation.sendMessageAsync(
          contents,
          object : MessageCallback {
            override fun onMessage(message: Message) {
              // Mirror LlmChatModelHelper line 318:
              //   resultListener(message.toString(), false, message.channels["thought"])
              //
              // message.channels["thought"] delivers DELTA text (incremental) on each callback.
              // When non-null and non-empty, emit a thought chunk first, then the content chunk.
              // When thinking=false the map will always be empty; this branch is unreachable.
              val thoughtDelta = message.channels[THOUGHT_CHANNEL_KEY]
              if (!thoughtDelta.isNullOrEmpty()) {
                trySend(StreamChunk(thought = thoughtDelta))
              }

              val contentChunk = message.toString()
              if (contentChunk.isNotEmpty()) {
                trySend(StreamChunk(content = contentChunk))
              }
            }

            override fun onDone() {
              Log.d(TAG, "generateStream: onDone")
              close() // closes the callbackFlow normally; awaitClose will run → Path C
            }

            override fun onError(throwable: Throwable) {
              Log.e(TAG, "generateStream: onError", throwable)
              close(throwable) // awaitClose will run → Path C
            }
          },
          extraContext,
        )
      } catch (e: Exception) {
        Log.e(TAG, "generateStream: sendMessageAsync setup failed", e)
        try { conversation.close() } catch (_: Exception) {}
        releaseMutex() // Path B unlock
        close(e)
        return@callbackFlow
      }

      // Path C: normal completion, onError, or cancellation — awaitClose is always called here.
      awaitClose {
        Log.d(TAG, "generateStream: awaitClose — cancelling conversation")
        try { conversation.cancelProcess() } catch (_: Exception) {}
        try { conversation.close() } catch (_: Exception) {}
        releaseMutex() // Path C unlock
      }
    }.buffer(Channel.UNLIMITED) // prevent trySend from silently dropping tokens under backpressure
  }

  // ── Single-shot ───────────────────────────────────────────────────────────

  /**
   * Accumulates the full streaming output and returns a [GenerationResult].
   *
   * The mutex is held implicitly through [generateStream] for the entire duration.
   *
   * @param maxTokens Not consumed per-request in LiteRT-LM 0.11.0 — the engine-level cap is
   *   applied at init time via [initMaxTokens].  Accepted for interface compatibility.
   *
   * Token counts are ESTIMATED (chars / 4) — LiteRT-LM 0.11.0 exposes no token-count API.
   */
  override suspend fun generate(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
    thinking: Boolean,
  ): GenerationResult {
    val chunks = generateStream(prompt, images, maxTokens, temperature, thinking).toList()
    val text = chunks.mapNotNull { it.content }.joinToString("")
    val reasoningText = chunks.mapNotNull { it.thought }.joinToString("").takeIf { it.isNotEmpty() }
    // ESTIMATED: LiteRT-LM 0.11.0 does not expose token counts in Message or Engine.
    val promptTokens = (prompt.length / 4).coerceAtLeast(1)
    val completionTokens = (text.length / 4).coerceAtLeast(1)
    return GenerationResult(
      text = text,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      reasoningText = reasoningText,
    )
  }

  // ── Cleanup ───────────────────────────────────────────────────────────────

  /**
   * Closes the underlying [Engine], releasing all native resources.
   *
   * Acquires the single-flight [mutex] before closing so that teardown waits for any in-flight
   * request to finish.  The mutex is intentionally NOT unlocked after — the engine is dead and
   * this instance must not be used again after [close] returns.
   */
  override fun close() {
    Log.d(TAG, "Closing engine — waiting for in-flight request...")
    // Block until any in-flight generate/generateStream completes, then close.
    runBlocking { mutex.lock() }
    Log.d(TAG, "Mutex acquired; closing engine.")
    try {
      engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close engine", e)
    }
    // Intentionally no mutex.unlock() — engine is dead; instance is unusable after this point.
  }

  // ── Internals ─────────────────────────────────────────────────────────────

  /**
   * Creates a fresh [Conversation] mirroring [LlmChatModelHelper]'s [resetConversation] pattern.
   *
   * [SamplerConfig] is always applied (FAROL v1 uses GPU backend, never NPU/TPU).
   * Temperature uses the [temperature] argument when provided; falls back to 1.0f (model default).
   */
  private fun createConversation(temperature: Float?) =
    engine.createConversation(
      ConversationConfig(
        samplerConfig = SamplerConfig(
          topK = 64,
          topP = 0.95,
          temperature = (temperature ?: 1.0f).toDouble(),
        ),
      )
    )

  /**
   * Builds a [Contents] from images + text.
   *
   * Mirror helper comment: "add the text after image and audio for the accurate last token".
   * Images are passed as [Content.ImageBytes] using the raw encoded bytes directly — no
   * BitmapFactory decode/re-encode round-trip.  The helper's Bitmap path only exists because it
   * starts from a [android.graphics.Bitmap]; we already have encoded bytes (PNG/JPEG) and
   * [Content.ImageBytes] accepts them as-is.  Removing the round-trip also eliminates Android-API
   * surface (BitmapFactory, Bitmap.compress) from this pure-data method.
   */
  private fun buildContents(prompt: String, images: List<ByteArray>): Contents {
    val contents = mutableListOf<Content>()
    for (rawBytes in images) {
      // Pass encoded bytes directly; let the engine validate the format.
      contents.add(Content.ImageBytes(rawBytes))
    }
    // Add the text after image for the accurate last token (mirror helper).
    if (prompt.trim().isNotEmpty()) {
      contents.add(Content.Text(prompt))
    }
    return Contents.of(contents)
  }
}

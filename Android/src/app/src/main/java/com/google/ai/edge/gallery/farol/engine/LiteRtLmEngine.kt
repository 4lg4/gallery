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

import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
   * The single-flight [mutex] is acquired before the flow starts and released when the flow
   * terminates (normally, with error, or via cancellation).
   */
  override fun generateStream(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
  ): Flow<String> = callbackFlow {
    // Acquire the mutex for the full flow lifetime.
    mutex.lock()
    Log.d(TAG, "generateStream: acquired mutex, prompt=${prompt.take(80)}...")

    val conversation = createConversation(temperature)
    try {
      val contents = buildContents(prompt, images)
      conversation.sendMessageAsync(
        contents,
        object : MessageCallback {
          override fun onMessage(message: Message) {
            // Mirror helper: message.toString() extracts the chunk text.
            val chunk = message.toString()
            if (chunk.isNotEmpty()) {
              trySend(chunk)
            }
          }

          override fun onDone() {
            Log.d(TAG, "generateStream: onDone")
            close() // closes the callbackFlow normally
          }

          override fun onError(throwable: Throwable) {
            Log.e(TAG, "generateStream: onError", throwable)
            close(throwable)
          }
        },
        emptyMap(),
      )
    } catch (e: Exception) {
      // Failed to start sendMessageAsync — close with the error.
      Log.e(TAG, "generateStream: sendMessageAsync setup failed", e)
      conversation.close()
      mutex.unlock()
      close(e)
      return@callbackFlow
    }

    awaitClose {
      // Called on flow cancellation OR after close()/close(throwable) above.
      // Mirror helper's stopResponse: cancelProcess() stops ongoing generation.
      Log.d(TAG, "generateStream: awaitClose — cancelling conversation")
      try {
        conversation.cancelProcess()
      } catch (_: Exception) {}
      try {
        conversation.close()
      } catch (_: Exception) {}
      mutex.unlock()
    }
  }

  // ── Single-shot ───────────────────────────────────────────────────────────

  /**
   * Accumulates the full streaming output and returns a [GenerationResult].
   *
   * The mutex is held implicitly through [generateStream] for the entire duration.
   *
   * Token counts are ESTIMATED (chars / 4) — LiteRT-LM 0.11.0 exposes no token-count API.
   */
  override suspend fun generate(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
  ): GenerationResult {
    val chunks = generateStream(prompt, images, maxTokens, temperature).toList()
    val text = chunks.joinToString("")
    // ESTIMATED: LiteRT-LM 0.11.0 does not expose token counts in Message or Engine.
    val promptTokens = (prompt.length / 4).coerceAtLeast(1)
    val completionTokens = (text.length / 4).coerceAtLeast(1)
    return GenerationResult(
      text = text,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
    )
  }

  // ── Cleanup ───────────────────────────────────────────────────────────────

  /** Closes the underlying [Engine], releasing all native resources. */
  override fun close() {
    Log.d(TAG, "Closing engine.")
    try {
      engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close engine: ${e.message}")
    }
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
   * Images are encoded as [Content.ImageBytes] — we already have raw encoded bytes (PNG/JPEG)
   * so we pass them directly.  If the bytes do not decode as a valid Bitmap (e.g. partial PNG
   * header from tests), we re-encode via BitmapFactory as a fallback, mirroring the helper's
   * `Bitmap.toPngByteArray()` path.
   */
  private fun buildContents(prompt: String, images: List<ByteArray>): Contents {
    val contents = mutableListOf<Content>()
    for (rawBytes in images) {
      // Prefer direct byte pass-through; fall back to decode+re-encode via BitmapFactory
      // (mirrors helper's Bitmap.toPngByteArray() for cases where format is non-PNG).
      val pngBytes = try {
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
        if (bitmap != null) {
          val out = ByteArrayOutputStream()
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
          out.toByteArray()
        } else {
          rawBytes // pass through as-is; let the engine reject if invalid
        }
      } catch (_: Exception) {
        rawBytes
      }
      contents.add(Content.ImageBytes(pngBytes))
    }
    // Add the text after image for the accurate last token (mirror helper).
    if (prompt.trim().isNotEmpty()) {
      contents.add(Content.Text(prompt))
    }
    return Contents.of(contents)
  }
}

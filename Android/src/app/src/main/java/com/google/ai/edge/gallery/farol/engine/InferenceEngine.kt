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

import kotlinx.coroutines.flow.Flow

/**
 * A single streaming chunk from [InferenceEngine.generateStream].
 *
 * Exactly one of [content] / [thought] will be non-null per chunk; both may be null only for the
 * terminal sentinel (an implementation detail — callers should ignore all-null chunks).
 *
 * @property content Incremental assistant text token (null when this chunk carries a thought).
 * @property thought Incremental thinking token from the "thought" channel (null for regular content
 *   chunks).  Only populated when thinking=true was requested.
 */
data class StreamChunk(
  val content: String? = null,
  val thought: String? = null,
)

/**
 * Result of a complete (non-streaming) generation request.
 *
 * @property text The generated text.
 * @property promptTokens Number of tokens in the input prompt.
 * @property completionTokens Number of tokens in the generated completion.
 * @property reasoningText Accumulated thinking/reasoning text from the "thought" channel, or null
 *   when thinking was not enabled for this request.
 */
data class GenerationResult(
  val text: String,
  val promptTokens: Int,
  val completionTokens: Int,
  val reasoningText: String? = null,
)

/**
 * Abstraction over an on-device LLM inference backend.
 *
 * Implementations must be thread-safe: concurrent [generate] / [generateStream] calls must be
 * serialized internally (e.g. via a [kotlinx.coroutines.sync.Mutex]).
 *
 * Implements [java.io.Closeable] so engines can be used in try-with-resources / `use {}` blocks.
 */
interface InferenceEngine : java.io.Closeable {

  /** The display name of the loaded model (e.g. "Gemma-4-E4B-it"). */
  val modelName: String

  /**
   * Single-flight blocking generation.
   *
   * Implementations serialize concurrent calls.  The call suspends until the full response is
   * ready.
   *
   * @param prompt Flattened text prompt.
   * @param images Raw encoded image bytes (PNG/JPEG) to prepend before the text.
   * @param maxTokens Maximum number of tokens to generate.  Best-effort: the implementation may
   *   apply this cap at engine-init time rather than per-request (see concrete class KDoc).
   * @param temperature Sampling temperature, or null to use the engine default.
   * @param thinking When true, enables Gemma 4 thinking mode; [GenerationResult.reasoningText] will
   *   be populated with the accumulated "thought" channel output.  When false (default),
   *   [GenerationResult.reasoningText] is null and behaviour is identical to prior versions.
   * @param systemInstruction Optional system instruction text.  When non-null, passed as
   *   [ConversationConfig.systemInstruction] to the underlying LiteRT-LM engine.  System-role
   *   messages and tool prompts are folded here by the route; the prompt text contains only the
   *   conversation turns.
   * @return [GenerationResult] with the generated text and token counts.
   */
  suspend fun generate(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
    thinking: Boolean = false,
    systemInstruction: String? = null,
  ): GenerationResult

  /**
   * Streaming generation.
   *
   * Emits [StreamChunk]s as they arrive from the model.  Each chunk carries either a content delta
   * ([StreamChunk.content]) or a thought delta ([StreamChunk.thought]) — never both in the same
   * chunk.  The flow completes normally when generation ends, or with an exception on error.
   * Cancelling the collecting coroutine cancels the underlying inference.
   *
   * Implementations hold the single-flight mutex for the flow's entire lifetime.
   *
   * @param prompt Flattened text prompt.
   * @param images Raw encoded image bytes (PNG/JPEG) to prepend before the text.
   * @param maxTokens Maximum number of tokens to generate.  Best-effort: the implementation may
   *   apply this cap at engine-init time rather than per-request (see concrete class KDoc).
   * @param temperature Sampling temperature, or null to use the engine default.
   * @param thinking When true, enables Gemma 4 thinking mode; [StreamChunk.thought] chunks will be
   *   interleaved with [StreamChunk.content] chunks.  When false (default), all chunks carry only
   *   [StreamChunk.content] and behaviour is identical to prior versions.
   * @param systemInstruction Optional system instruction text.  When non-null, passed as
   *   [ConversationConfig.systemInstruction] to the underlying LiteRT-LM engine.
   * @return [Flow] of [StreamChunk]s.
   */
  fun generateStream(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
    thinking: Boolean = false,
    systemInstruction: String? = null,
  ): Flow<StreamChunk>

  /**
   * Releases all native resources held by this engine.
   *
   * After [close] the engine must not be used.
   */
  override fun close()
}

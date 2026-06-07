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
 * Result of a complete (non-streaming) generation request.
 *
 * @property text The generated text.
 * @property promptTokens Number of tokens in the input prompt.
 * @property completionTokens Number of tokens in the generated completion.
 */
data class GenerationResult(
  val text: String,
  val promptTokens: Int,
  val completionTokens: Int,
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
   * @return [GenerationResult] with the generated text and token counts.
   */
  suspend fun generate(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
  ): GenerationResult

  /**
   * Streaming generation.
   *
   * Emits text chunks as they arrive from the model.  The flow completes normally when generation
   * ends, or with an exception on error.  Cancelling the collecting coroutine cancels the
   * underlying inference.
   *
   * Implementations hold the single-flight mutex for the flow's entire lifetime.
   *
   * @param prompt Flattened text prompt.
   * @param images Raw encoded image bytes (PNG/JPEG) to prepend before the text.
   * @param maxTokens Maximum number of tokens to generate.  Best-effort: the implementation may
   *   apply this cap at engine-init time rather than per-request (see concrete class KDoc).
   * @param temperature Sampling temperature, or null to use the engine default.
   * @return [Flow] of text chunks.
   */
  fun generateStream(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
  ): Flow<String>

  /**
   * Releases all native resources held by this engine.
   *
   * After [close] the engine must not be used.
   */
  override fun close()
}

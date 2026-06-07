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
import kotlinx.coroutines.flow.asFlow

/**
 * Test-only [InferenceEngine] that emits a fixed list of chunks without touching the LiteRT-LM
 * native runtime.
 *
 * Reused across Task 4 route tests.
 *
 * @param chunks Text fragments emitted by [generateStream].  Joined for [generate].
 * @param promptTokensOverride If set, used as [GenerationResult.promptTokens]; else estimated.
 * @param completionTokensOverride If set, used as [GenerationResult.completionTokens]; else
 *   estimated.
 */
class FakeInferenceEngine(
  private val chunks: List<String> = listOf("Hello", ", ", "world", "!"),
  private val promptTokensOverride: Int? = null,
  private val completionTokensOverride: Int? = null,
  override val modelName: String = "fake-model",
) : InferenceEngine {

  /** Tracks arguments from the last [generate] or [generateStream] call (for assertion). */
  var lastPrompt: String? = null
    private set
  var lastImages: List<ByteArray>? = null
    private set
  var lastMaxTokens: Int? = null
    private set
  var lastTemperature: Float? = null
    private set

  var closeCalled = false
    private set

  override fun generateStream(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
  ): Flow<String> {
    record(prompt, images, maxTokens, temperature)
    return chunks.asFlow()
  }

  override suspend fun generate(
    prompt: String,
    images: List<ByteArray>,
    maxTokens: Int,
    temperature: Float?,
  ): GenerationResult {
    record(prompt, images, maxTokens, temperature)
    val text = chunks.joinToString("")
    return GenerationResult(
      text = text,
      promptTokens = promptTokensOverride ?: (prompt.length / 4).coerceAtLeast(1),
      completionTokens = completionTokensOverride ?: (text.length / 4).coerceAtLeast(1),
    )
  }

  override fun close() {
    closeCalled = true
  }

  private fun record(prompt: String, images: List<ByteArray>, maxTokens: Int, temperature: Float?) {
    lastPrompt = prompt
    lastImages = images
    lastMaxTokens = maxTokens
    lastTemperature = temperature
  }
}

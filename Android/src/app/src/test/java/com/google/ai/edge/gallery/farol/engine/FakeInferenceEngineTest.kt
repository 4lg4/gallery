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

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [FakeInferenceEngine] satisfies the [InferenceEngine] contract and that the
 * interface is implementable on the JVM without native libraries.
 */
class FakeInferenceEngineTest {

  @Test
  fun `FakeInferenceEngine implements InferenceEngine`() {
    // Compile-time check: assignment proves the interface is satisfied.
    val engine: InferenceEngine = FakeInferenceEngine()
    assertEquals("fake-model", engine.modelName)
  }

  @Test
  fun `generateStream emits expected content chunks in order`() = runBlocking {
    val engine = FakeInferenceEngine(chunks = listOf("Hello", ", ", "world", "!"))
    val collected = engine.generateStream("prompt", emptyList(), 128, null).toList()
    assertEquals(
      listOf("Hello", ", ", "world", "!"),
      collected.mapNotNull { it.content },
    )
    assertTrue(collected.all { it.thought == null }, "no thought chunks expected")
  }

  @Test
  fun `generateStream emits single content chunk`() = runBlocking {
    val engine = FakeInferenceEngine(chunks = listOf("hi"))
    val collected = engine.generateStream("x", emptyList(), 64, null).toList()
    assertEquals(1, collected.size)
    assertEquals("hi", collected[0].content)
    assertEquals(null, collected[0].thought)
  }

  @Test
  fun `generate joins chunks into full text`() = runBlocking {
    val engine = FakeInferenceEngine(chunks = listOf("A", "B", "C"))
    val result = engine.generate("prompt", emptyList(), 256, null)
    assertEquals("ABC", result.text)
  }

  @Test
  fun `generate records last call arguments`() = runBlocking {
    val engine = FakeInferenceEngine()
    val img = byteArrayOf(1, 2, 3)
    engine.generate("test prompt", listOf(img), 512, 0.7f)
    assertEquals("test prompt", engine.lastPrompt)
    assertEquals(1, engine.lastImages?.size)
    assertEquals(512, engine.lastMaxTokens)
    assertEquals(0.7f, engine.lastTemperature)
  }

  @Test
  fun `generate estimates token counts when overrides not set`() = runBlocking {
    val engine = FakeInferenceEngine(chunks = listOf("hello"))
    val result = engine.generate("test", emptyList(), 64, null)
    assertTrue(result.promptTokens >= 1)
    assertTrue(result.completionTokens >= 1)
  }

  @Test
  fun `generate uses token overrides when provided`() = runBlocking {
    val engine = FakeInferenceEngine(
      chunks = listOf("x"),
      promptTokensOverride = 42,
      completionTokensOverride = 7,
    )
    val result = engine.generate("any", emptyList(), 64, null)
    assertEquals(42, result.promptTokens)
    assertEquals(7, result.completionTokens)
  }

  @Test
  fun `close sets closeCalled flag`() {
    val engine = FakeInferenceEngine()
    assertFalse(engine.closeCalled)
    engine.close()
    assertTrue(engine.closeCalled)
  }

  @Test
  fun `generateStream with custom model name`() = runBlocking {
    val engine: InferenceEngine = FakeInferenceEngine(modelName = "my-custom-model")
    assertEquals("my-custom-model", engine.modelName)
  }

  // ── thinking mode tests ───────────────────────────────────────────────────

  @Test
  fun `generateStream thinking=true emits thought chunks before content chunks`() = runBlocking {
    val engine = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("think1", "think2"),
    )
    val collected = engine.generateStream("prompt", emptyList(), 128, null, thinking = true).toList()
    val thoughts = collected.filter { it.thought != null }
    val contents = collected.filter { it.content != null }
    assertEquals(listOf("think1", "think2"), thoughts.map { it.thought })
    assertEquals(listOf("answer"), contents.map { it.content })
    // thought chunks come first (in the order we emit them)
    val thoughtIndices = collected.indices.filter { collected[it].thought != null }
    val contentIndices = collected.indices.filter { collected[it].content != null }
    assertTrue(thoughtIndices.max() < contentIndices.min(), "thought chunks must precede content")
  }

  @Test
  fun `generateStream thinking=false emits no thought chunks even when thoughtChunks configured`() = runBlocking {
    val engine = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("think1"),
    )
    val collected = engine.generateStream("prompt", emptyList(), 128, null, thinking = false).toList()
    assertTrue(collected.all { it.thought == null }, "no thought chunks when thinking=false")
    assertEquals(listOf("answer"), collected.mapNotNull { it.content })
  }

  @Test
  fun `generate thinking=true populates reasoningText`() = runBlocking {
    val engine = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("step1 ", "step2"),
    )
    val result = engine.generate("prompt", emptyList(), 128, null, thinking = true)
    assertEquals("answer", result.text)
    assertEquals("step1 step2", result.reasoningText)
  }

  @Test
  fun `generate thinking=false has null reasoningText`() = runBlocking {
    val engine = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("step1"),
    )
    val result = engine.generate("prompt", emptyList(), 128, null, thinking = false)
    assertEquals("answer", result.text)
    assertEquals(null, result.reasoningText)
  }

  @Test
  fun `generate records thinking flag`() = runBlocking {
    val engine = FakeInferenceEngine()
    engine.generate("prompt", emptyList(), 64, null, thinking = true)
    assertEquals(true, engine.lastThinking)
  }

  @Test
  fun `GenerationResult data class equality`() {
    val a = GenerationResult(text = "hi", promptTokens = 1, completionTokens = 2)
    val b = GenerationResult(text = "hi", promptTokens = 1, completionTokens = 2)
    assertEquals(a, b)
  }

  @Test
  fun `GenerationResult copy with different text`() {
    val original = GenerationResult(text = "hello", promptTokens = 5, completionTokens = 3)
    val copy = original.copy(text = "world")
    assertEquals("world", copy.text)
    assertEquals(5, copy.promptTokens)
    assertEquals(3, copy.completionTokens)
  }

  // ── throwOnGenerate error-path tests ─────────────────────────────────────

  @Test
  fun `generate throws when throwOnGenerate is set`() = runBlocking {
    val boom = RuntimeException("engine exploded")
    val engine = FakeInferenceEngine(
      chunks = listOf("partial"),
      throwOnGenerate = boom,
    )
    val thrown = assertFailsWith<RuntimeException> {
      engine.generate("prompt", emptyList(), 64, null)
    }
    assertEquals("engine exploded", thrown.message)
    // call was still recorded before the throw
    assertEquals("prompt", engine.lastPrompt)
  }

  @Test
  fun `generateStream emits chunks then throws when throwOnGenerate is set`() = runBlocking {
    val boom = IllegalStateException("mid-stream failure")
    val engine = FakeInferenceEngine(
      chunks = listOf("A", "B", "C"),
      throwOnGenerate = boom,
    )
    val collected = mutableListOf<StreamChunk>()
    val thrown = assertFailsWith<IllegalStateException> {
      engine.generateStream("prompt", emptyList(), 64, null).collect { collected.add(it) }
    }
    assertEquals(listOf("A", "B", "C"), collected.mapNotNull { it.content })
    assertEquals("mid-stream failure", thrown.message)
  }
}

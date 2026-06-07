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

package com.google.ai.edge.gallery.farol.server

import com.google.ai.edge.gallery.farol.engine.FakeInferenceEngine
import com.google.ai.edge.gallery.farol.openai.ChatCompletionChunk
import com.google.ai.edge.gallery.farol.openai.ChatCompletionResponse
import com.google.ai.edge.gallery.farol.openai.OpenAIDecoder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val THINKING_KEY = "thinking-test-key"

/**
 * Tests for the opt-in thinking / reasoning_content feature (V2-C).
 *
 * All tests that exercise thinking=true use a [FakeInferenceEngine] configured with [thoughtChunks]
 * so no LiteRT-LM native code is involved.
 */
class ThinkingTest {

  // ── Request parsing ──────────────────────────────────────────────────────────

  @Test
  fun `thinking field defaults to false when omitted from request`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("step1"),
    )
    application { farolModule(fake, THINKING_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    // No thinking flag → engine receives thinking=false
    assertEquals(false, fake.lastThinking)
  }

  @Test
  fun `thinking field parses to true when explicitly set`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("step1"),
    )
    application { farolModule(fake, THINKING_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(true, fake.lastThinking)
  }

  // ── Batch mode — thinking=true ────────────────────────────────────────────

  @Test
  fun `batch thinking=true returns reasoning_content on assistant message`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("The answer is 42."),
      thoughtChunks = listOf("Let me think... ", "step2"),
      promptTokensOverride = 5,
      completionTokensOverride = 10,
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":true,"messages":[{"role":"user","content":"what is 6*7?"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("The answer is 42.", body.choices[0].message.content)
    assertNotNull(body.choices[0].message.reasoningContent, "reasoning_content must be present")
    assertEquals("Let me think... step2", body.choices[0].message.reasoningContent)
  }

  @Test
  fun `batch thinking=false has null reasoning_content`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("hidden thought"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":false,"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertNull(body.choices[0].message.reasoningContent, "reasoning_content must be null when thinking=false")
  }

  @Test
  fun `batch thinking omitted has null reasoning_content`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("hidden thought"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertNull(body.choices[0].message.reasoningContent, "reasoning_content must be null when thinking not requested")
  }

  // ── Streaming mode — thinking=true ───────────────────────────────────────

  @Test
  fun `stream thinking=true interleaves reasoning_content deltas before content deltas`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("t1", "t2"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":true,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    // Collect reasoning_content and content deltas from all chunks
    val reasoningDeltas = events.mapNotNull { line ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(line)
      chunk.choices[0].delta.reasoningContent
    }
    val contentDeltas = events.mapNotNull { line ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(line)
      chunk.choices[0].delta.content
    }
    assertEquals(listOf("t1", "t2"), reasoningDeltas, "reasoning deltas mismatch: $raw")
    assertTrue(contentDeltas.contains("answer"), "answer not in content deltas: $contentDeltas")
  }

  @Test
  fun `stream thinking=true reasoning_content deltas are decodable as ChatCompletionChunk`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("final"),
      thoughtChunks = listOf("reasoning step"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":true,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    // Every JSON event must decode cleanly as ChatCompletionChunk
    events.forEach { line ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(line)
      assertNotNull(chunk.id)
    }
    // The thought chunk must have null content and non-null reasoning_content
    val thoughtChunks = events.map { OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it) }
      .filter { it.choices[0].delta.reasoningContent != null }
    assertEquals(1, thoughtChunks.size)
    assertNull(thoughtChunks[0].choices[0].delta.content)
    assertEquals("reasoning step", thoughtChunks[0].choices[0].delta.reasoningContent)
  }

  @Test
  fun `stream thinking=false has no reasoning_content in any chunk`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("hidden thought"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":false,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    events.forEach { line ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(line)
      assertNull(
        chunk.choices[0].delta.reasoningContent,
        "unexpected reasoning_content in chunk: $line",
      )
    }
  }

  @Test
  fun `stream thinking omitted has no reasoning_content in any chunk`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("hidden thought"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    events.forEach { line ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(line)
      assertNull(
        chunk.choices[0].delta.reasoningContent,
        "unexpected reasoning_content in chunk: $line",
      )
    }
  }

  @Test
  fun `stream thinking=true finish_reason and DONE are unchanged`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("answer"),
      thoughtChunks = listOf("thought"),
    )
    application { farolModule(fake, THINKING_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $THINKING_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"thinking":true,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    assertTrue(raw.contains("data: [DONE]\n\n"), "no properly-framed DONE in: $raw")
    val events = parseSSELines(raw)
    val stopChunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(events.last())
    assertEquals("stop", stopChunk.choices[0].finishReason)
  }

  // ── ModelsSerializationTest-style: request round-trip ────────────────────

  @Test
  fun `ChatCompletionResponse reasoning_content is null by default`() {
    val json = """{"id":"x","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
    val response = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(json)
    assertNull(response.choices[0].message.reasoningContent)
  }

  @Test
  fun `ChatCompletionResponse decodes reasoning_content when present`() {
    val json = """{"id":"x","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi","reasoning_content":"think"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
    val response = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(json)
    assertEquals("think", response.choices[0].message.reasoningContent)
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /** Extracts JSON payloads from SSE "data: <json>" lines, skipping the [DONE] sentinel. */
  private fun parseSSELines(raw: String): List<String> =
    raw.split("\n\n")
      .map { it.trim() }
      .filter { it.startsWith("data: ") }
      .map { it.removePrefix("data: ") }
      .filter { it != "[DONE]" && it.isNotBlank() }
}

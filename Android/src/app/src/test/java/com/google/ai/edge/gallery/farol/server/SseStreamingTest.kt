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
import kotlin.test.assertTrue

private const val SSE_KEY = "sse-test-key"

class SseStreamingTest {

  private fun streamRequest(
    fake: FakeInferenceEngine = FakeInferenceEngine(),
    body: String = """{"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
    block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit = {},
  ) = testApplication {
    application { farolModule(fake, SSE_KEY) }
    block()
  }

  @Test
  fun `stream returns content-type text-event-stream`() = testApplication {
    application { farolModule(FakeInferenceEngine(), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val ct = resp.headers["Content-Type"] ?: ""
    assertTrue(ct.contains("text/event-stream"), "content-type=$ct")
  }

  @Test
  fun `stream first event has delta with role assistant`() = testApplication {
    application { farolModule(FakeInferenceEngine(chunks = listOf("Hello")), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    assertTrue(events.isNotEmpty(), "no events in: $raw")
    val firstChunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(events.first())
    assertEquals("assistant", firstChunk.choices[0].delta.role)
  }

  @Test
  fun `stream middle events carry fake chunks in order`() = testApplication {
    val chunks = listOf("Hello", ", ", "world")
    application { farolModule(FakeInferenceEngine(chunks = chunks), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    // events = [role-event, chunk1, chunk2, chunk3, stop-event]; skip first (role) and last (stop)
    val contentEvents = events.drop(1).dropLast(1)
    assertEquals(chunks.size, contentEvents.size, "events=$events")
    contentEvents.forEachIndexed { i, jsonLine ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(jsonLine)
      assertEquals(chunks[i], chunk.choices[0].delta.content)
    }
  }

  @Test
  fun `stream last data event before DONE has finish_reason stop`() = testApplication {
    application { farolModule(FakeInferenceEngine(chunks = listOf("Hi")), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    val stopChunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(events.last())
    assertEquals("stop", stopChunk.choices[0].finishReason)
  }

  @Test
  fun `stream terminates with DONE line`() = testApplication {
    application { farolModule(FakeInferenceEngine(), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    assertTrue(raw.contains("data: [DONE]"), "no DONE in: $raw")
  }

  @Test
  fun `stream all chunks share same id`() = testApplication {
    val chunks = listOf("A", "B", "C")
    application { farolModule(FakeInferenceEngine(chunks = chunks), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    val ids = events.map { OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it).id }.toSet()
    assertEquals(1, ids.size, "chunk ids differ: $ids")
    assertTrue(ids.first().startsWith("chatcmpl-"), "id=${ids.first()}")
  }

  @Test
  fun `stream each json line is decodable as ChatCompletionChunk`() = testApplication {
    application { farolModule(FakeInferenceEngine(chunks = listOf("Foo", "Bar")), SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    events.forEach { line ->
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(line)
      assertNotNull(chunk.id)
    }
  }

  @Test
  fun `stream error path emits chunks then error event then stops`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("partial"),
      throwOnGenerate = RuntimeException("engine blew up"),
    )
    application { farolModule(fake, SSE_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $SSE_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    // Should contain partial chunk
    assertTrue(raw.contains("\"partial\"") || raw.contains("partial"), "raw=$raw")
    // Should contain error event
    assertTrue(raw.contains("server_error"), "no server_error in: $raw")
  }

  /**
   * Extracts JSON lines from SSE "data: <json>" lines, skipping [DONE] and blank lines.
   */
  private fun parseSSELines(raw: String): List<String> =
    raw.lines()
      .filter { it.startsWith("data: ") }
      .map { it.removePrefix("data: ").trim() }
      .filter { it != "[DONE]" && it.isNotBlank() }
}

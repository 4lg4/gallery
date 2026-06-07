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

private const val TOOLS_KEY = "tools-test-key"

/**
 * Tests for the OpenAI tools / function-calling wire (V2-D).
 *
 * All tests use [FakeInferenceEngine] configured with canned text that either contains a
 * `\`\`\`tool_call` block or plain text, so no LiteRT-LM native code is involved.
 */
class ToolsRouteTest {

  private val toolsJson = """
    [{"type":"function","function":{"name":"get_weather","description":"Get current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]
  """.trimIndent()

  private val toolCallResponse = """
    ```tool_call
    {"name": "get_weather", "arguments": {"city": "Perth"}}
    ```
  """.trimIndent()

  // ── Batch with tools → tool_calls ─────────────────────────────────────────

  @Test
  fun `batch with tools returns tool_calls array`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"What is the weather?"}],"tools":$toolsJson}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertNotNull(body.choices[0].message.toolCalls, "tool_calls must be present")
    assertEquals(1, body.choices[0].message.toolCalls!!.size)
    assertEquals("get_weather", body.choices[0].message.toolCalls!![0].function.name)
  }

  @Test
  fun `batch with tools has finish_reason tool_calls`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("tool_calls", body.choices[0].finishReason)
  }

  @Test
  fun `batch tool_calls arguments round-trip via Json parse`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    val argsString = body.choices[0].message.toolCalls!![0].function.arguments
    // arguments must be a valid JSON string (per OpenAI spec)
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(argsString).jsonObject
    assertNotNull(parsed["city"])
  }

  @Test
  fun `batch tool_calls id has call_ prefix`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    val id = body.choices[0].message.toolCalls!![0].id
    assertTrue(id.startsWith("call_"), "id should start with call_: $id")
  }

  @Test
  fun `batch tool_calls type is function`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("function", body.choices[0].message.toolCalls!![0].type)
  }

  // ── Batch with tools but plain answer → normal response ───────────────────

  @Test
  fun `batch with tools but plain answer returns normal content`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("The weather is sunny."))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("stop", body.choices[0].finishReason)
    assertNull(body.choices[0].message.toolCalls)
    assertEquals("The weather is sunny.", body.choices[0].message.content)
  }

  // ── tool_choice="none" suppresses tool folding ────────────────────────────

  @Test
  fun `tool_choice none suppresses tool prompt injection`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Hello"))
    application { farolModule(fake, TOOLS_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}],"tools":$toolsJson,"tool_choice":"none"}""")
    }
    // When tool_choice=none, no systemInstruction should be built for tools
    assertNull(fake.lastSystemInstruction, "systemInstruction should be null when tool_choice=none")
  }

  @Test
  fun `tool_choice none falls back to normal response`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Normal answer"))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}],"tools":$toolsJson,"tool_choice":"none"}""")
    }
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("stop", body.choices[0].finishReason)
    assertNull(body.choices[0].message.toolCalls)
  }

  // ── Multi-turn with tool results → flattened prompt ───────────────────────

  @Test
  fun `multi-turn with assistant tool_calls is rendered in flattened prompt`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Done"))
    application { farolModule(fake, TOOLS_KEY) }
    val body = """
      {
        "messages": [
          {"role":"user","content":"What is the weather?"},
          {"role":"assistant","content":null,"tool_calls":[{"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Perth\"}"}}]},
          {"role":"tool","tool_call_id":"call_abc","content":"22°C, sunny"}
        ],
        "tools": $toolsJson
      }
    """.trimIndent()
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    // The flattened prompt must contain both the tool call rendering and the tool result
    val prompt = fake.lastPrompt ?: ""
    assertTrue(prompt.contains("get_weather"), "prompt must contain tool call: $prompt")
    assertTrue(prompt.contains("call_abc"), "prompt must contain tool result id: $prompt")
    assertTrue(prompt.contains("22°C"), "prompt must contain tool result content: $prompt")
  }

  @Test
  fun `multi-turn tool result rendered as bracket format`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Done"))
    application { farolModule(fake, TOOLS_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""
        {"messages":[
          {"role":"user","content":"calc"},
          {"role":"tool","tool_call_id":"call_xyz","content":"42"}
        ],"tools":$toolsJson}
      """.trimIndent())
    }
    val prompt = fake.lastPrompt ?: ""
    assertTrue(prompt.contains("[tool call_xyz result]: 42"), "tool result not rendered: $prompt")
  }

  // ── System messages extracted to systemInstruction ────────────────────────

  @Test
  fun `system message is extracted to systemInstruction not prompt`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Hello"))
    application { farolModule(fake, TOOLS_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"system","content":"You are helpful."},{"role":"user","content":"hi"}]}""")
    }
    val prompt = fake.lastPrompt ?: ""
    val sysInstr = fake.lastSystemInstruction ?: ""
    assertTrue(sysInstr.contains("You are helpful."), "system message not in systemInstruction: $sysInstr")
    assertTrue(!prompt.contains("You are helpful."), "system message should not be in prompt: $prompt")
  }

  @Test
  fun `tools with system message builds combined systemInstruction`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Hello"))
    application { farolModule(fake, TOOLS_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"system","content":"You are helpful."},{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val sysInstr = fake.lastSystemInstruction ?: ""
    assertTrue(sysInstr.contains("You are helpful."), "system message not found in combined sysInstr: $sysInstr")
    assertTrue(sysInstr.contains("get_weather"), "tool prompt not found in combined sysInstr: $sysInstr")
  }

  @Test
  fun `no tools and no system message means null systemInstruction`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Hello"))
    application { farolModule(fake, TOOLS_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertNull(fake.lastSystemInstruction)
  }

  // ── Streaming with tools → buffered SSE shape ─────────────────────────────

  @Test
  fun `stream with tools returns SSE with role chunk`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    // First chunk must carry the role
    val firstChunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(events.first())
    assertEquals("assistant", firstChunk.choices[0].delta.role)
  }

  @Test
  fun `stream with tools has tool_calls delta decodable`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    // Find the chunk with tool_calls
    val toolCallChunk = events.mapNotNull {
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it)
      chunk.takeIf { c -> c.choices[0].delta.toolCalls != null }
    }.firstOrNull()
    assertNotNull(toolCallChunk, "No tool_calls delta found in SSE stream. events=$events")
    val toolCalls = toolCallChunk.choices[0].delta.toolCalls!!
    assertEquals(1, toolCalls.size)
    assertEquals("get_weather", toolCalls[0].function.name)
  }

  @Test
  fun `stream with tools has finish_reason tool_calls chunk`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    val finishChunk = events.mapNotNull {
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it)
      chunk.takeIf { c -> c.choices[0].finishReason == "tool_calls" }
    }.firstOrNull()
    assertNotNull(finishChunk, "No finish_reason=tool_calls chunk found. events=$events")
  }

  @Test
  fun `stream with tools ends with DONE`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(toolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val raw = resp.bodyAsText()
    assertTrue(raw.contains("data: [DONE]\n\n"), "SSE stream must end with [DONE]: $raw")
  }

  @Test
  fun `stream with tools plain answer returns stop finish reason`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Sunny today."))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"weather?"}],"tools":$toolsJson}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    val stopChunk = events.mapNotNull {
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it)
      chunk.takeIf { c -> c.choices[0].finishReason == "stop" }
    }.firstOrNull()
    assertNotNull(stopChunk, "No finish_reason=stop chunk found. events=$events")
  }

  // ── MULTI-CALL: batch with multiple tool_call blocks ─────────────────────

  private val twoToolCallResponse = """
    ```tool_call
    {"name": "get_weather", "arguments": {"city": "Perth"}}
    ```
    ```tool_call
    {"name": "get_forecast", "arguments": {"city": "Perth", "days": 3}}
    ```
  """.trimIndent()

  private val twoToolsJson = """
    [
      {"type":"function","function":{"name":"get_weather","description":"Get current weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}},
      {"type":"function","function":{"name":"get_forecast","description":"Get forecast","parameters":{"type":"object","properties":{"city":{"type":"string"},"days":{"type":"integer"}}}}}
    ]
  """.trimIndent()

  @Test
  fun `batch response with multiple tool_call blocks yields 2 tool_calls with unique ids`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(twoToolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather and forecast?"}],"tools":$twoToolsJson}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    val toolCalls = body.choices[0].message.toolCalls
    assertNotNull(toolCalls, "tool_calls must be present")
    assertEquals(2, toolCalls.size, "Expected 2 tool_calls, got ${toolCalls.size}")
    assertEquals("get_weather", toolCalls[0].function.name)
    assertEquals("get_forecast", toolCalls[1].function.name)
    // IDs must be unique
    val ids = toolCalls.map { it.id }
    assertEquals(2, ids.toSet().size, "tool_call ids must be unique: $ids")
    // Each id must be a valid JSON string (no nulls) parseable as a string
    ids.forEach { id ->
      assertTrue(id.startsWith("call_"), "id must start with call_: $id")
    }
    // arguments must be valid JSON strings
    toolCalls.forEach { tc ->
      val parsed = kotlinx.serialization.json.Json.parseToJsonElement(tc.function.arguments)
      assertNotNull(parsed)
    }
  }

  @Test
  fun `batch multiple tool_calls have valid arguments strings`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(twoToolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"weather and forecast?"}],"tools":$twoToolsJson}""")
    }
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    val toolCalls = body.choices[0].message.toolCalls!!
    // First call: city=Perth
    val args0 = kotlinx.serialization.json.Json.parseToJsonElement(toolCalls[0].function.arguments).jsonObject
    assertNotNull(args0["city"])
    // Second call: city=Perth, days=3
    val args1 = kotlinx.serialization.json.Json.parseToJsonElement(toolCalls[1].function.arguments).jsonObject
    assertNotNull(args1["city"])
    assertNotNull(args1["days"])
  }

  // ── MULTI-CALL: streaming with multiple tool calls has index 0 and 1 ─────

  @Test
  fun `streaming with multiple tool calls delta has index 0 and 1`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf(twoToolCallResponse))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"weather and forecast?"}],"tools":$twoToolsJson}""")
    }
    val raw = resp.bodyAsText()
    val events = parseSSELines(raw)
    // Find the delta chunk that carries tool_calls
    val toolCallChunk = events.mapNotNull {
      val chunk = OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it)
      chunk.takeIf { c -> c.choices[0].delta.toolCalls != null }
    }.firstOrNull()
    assertNotNull(toolCallChunk, "No tool_calls delta in SSE. events=$events")
    val toolCalls = toolCallChunk.choices[0].delta.toolCalls!!
    assertEquals(2, toolCalls.size, "Expected 2 tool_calls in streaming delta")
    // index values must be 0 and 1
    assertEquals(0, toolCalls[0].index, "First streaming tool_call index must be 0")
    assertEquals(1, toolCalls[1].index, "Second streaming tool_call index must be 1")
    // Decoded correctly via OpenAIDecoder
    assertEquals("get_weather", toolCalls[0].function.name)
    assertEquals("get_forecast", toolCalls[1].function.name)
  }

  // ── MULTI-CALL: batch prefixText + tool_call block → content + tool_calls ─

  @Test
  fun `batch prefixText case yields both content and tool_calls`() = testApplication {
    val prefixAndToolCall = "Let me check the weather.\n```tool_call\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Perth\"}}\n```"
    val fake = FakeInferenceEngine(chunks = listOf(prefixAndToolCall))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"What is the weather?"}],"tools":$toolsJson}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    val message = body.choices[0].message
    // content must hold the prefix prose
    assertNotNull(message.content, "content must be non-null for prefix prose")
    assertTrue(
      message.content!!.contains("Let me check the weather."),
      "content must contain prefix prose: ${message.content}"
    )
    // tool_calls must also be present
    assertNotNull(message.toolCalls, "tool_calls must be present alongside prefix prose")
    assertEquals(1, message.toolCalls!!.size)
    assertEquals("get_weather", message.toolCalls[0].function.name)
  }

  // ── Existing no-tools path unaffected ─────────────────────────────────────

  @Test
  fun `no tools batch still works normally`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("Hello", ", ", "world"),
      promptTokensOverride = 5,
      completionTokensOverride = 3,
    )
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("Hello, world", body.choices[0].message.content)
    assertEquals("stop", body.choices[0].finishReason)
    assertNull(body.choices[0].message.toolCalls)
  }

  @Test
  fun `no tools stream still works normally`() = testApplication {
    val fake = FakeInferenceEngine(chunks = listOf("Hi", " there"))
    application { farolModule(fake, TOOLS_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $TOOLS_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
    }
    val raw = resp.bodyAsText()
    assertTrue(raw.contains("data: [DONE]\n\n"), "DONE not found: $raw")
    val events = parseSSELines(raw)
    val stopChunk = events.map { OpenAIDecoder.decodeFromString<ChatCompletionChunk>(it) }
      .firstOrNull { it.choices[0].finishReason == "stop" }
    assertNotNull(stopChunk, "No stop chunk found")
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private fun parseSSELines(raw: String): List<String> =
    raw.split("\n\n")
      .map { it.trim() }
      .filter { it.startsWith("data: ") }
      .map { it.removePrefix("data: ") }
      .filter { it != "[DONE]" && it.isNotBlank() }

  private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject

  private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
}

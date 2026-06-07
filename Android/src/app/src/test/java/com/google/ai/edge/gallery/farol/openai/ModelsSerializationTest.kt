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

package com.google.ai.edge.gallery.farol.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsSerializationTest {

  private val lenient = Json { ignoreUnknownKeys = true }
  // encodeDefaults=true is required so that "object", "finish_reason", etc. are emitted
  // even though they have default values — matching the OpenAI wire format contract.
  private val encoder = Json { encodeDefaults = true }

  // ── ChatCompletionRequest ────────────────────────────────────────────────

  @Test
  fun `request with stream absent defaults to false`() {
    val json = """{"messages":[{"role":"user","content":"hi"}]}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertFalse(req.stream)
  }

  @Test
  fun `request with stream true parses correctly`() {
    val json = """{"messages":[{"role":"user","content":"hi"}],"stream":true}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertTrue(req.stream)
  }

  @Test
  fun `request max_tokens snake_case parsed`() {
    val json = """{"messages":[{"role":"user","content":"hi"}],"max_tokens":512}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertEquals(512, req.maxTokens)
  }

  @Test
  fun `request temperature parsed`() {
    val json = """{"messages":[{"role":"user","content":"hi"}],"temperature":0.7}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertEquals(0.7, req.temperature)
  }

  @Test
  fun `request with unknown field ignored when ignoreUnknownKeys=true`() {
    val json = """{"messages":[{"role":"user","content":"hello"}],"future_field":"whatever","stream":false}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertEquals(1, req.messages.size)
    assertFalse(req.stream)
  }

  @Test
  fun `request message content can be string`() {
    val json = """{"messages":[{"role":"user","content":"hello"}]}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertEquals(JsonPrimitive("hello"), req.messages[0].content)
  }

  @Test
  fun `request message content can be array`() {
    val json = """{"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}"""
    val req = lenient.decodeFromString<ChatCompletionRequest>(json)
    assertTrue(req.messages[0].content is kotlinx.serialization.json.JsonArray)
  }

  // ── ChatCompletionResponse ───────────────────────────────────────────────

  @Test
  fun `response object field encodes as chat completion`() {
    val resp = ChatCompletionResponse(
      id = "chatcmpl-123",
      created = 1000L,
      model = "farol-v1",
      choices = listOf(
        Choice(
          message = AssistantMessage(content = "Hello!"),
          finishReason = "stop"
        )
      ),
      usage = Usage(promptTokens = 5, completionTokens = 7, totalTokens = 12)
    )
    val encoded = encoder.encodeToString(ChatCompletionResponse.serializer(), resp)
    assertTrue(encoded.contains("\"object\":\"chat.completion\""))
  }

  @Test
  fun `response usage uses snake_case keys`() {
    val resp = ChatCompletionResponse(
      id = "chatcmpl-abc",
      created = 2000L,
      model = "farol-v1",
      choices = listOf(Choice(message = AssistantMessage(content = "ok"))),
      usage = Usage(promptTokens = 3, completionTokens = 4, totalTokens = 7)
    )
    val encoded = encoder.encodeToString(ChatCompletionResponse.serializer(), resp)
    assertTrue(encoded.contains("\"prompt_tokens\""))
    assertTrue(encoded.contains("\"completion_tokens\""))
    assertTrue(encoded.contains("\"total_tokens\""))
  }

  @Test
  fun `response choice default index is 0`() {
    val choice = Choice(message = AssistantMessage(content = "hi"))
    assertEquals(0, choice.index)
  }

  @Test
  fun `response assistant message default role is assistant`() {
    val msg = AssistantMessage(content = "hi")
    assertEquals("assistant", msg.role)
  }

  // ── ChatCompletionChunk ──────────────────────────────────────────────────

  @Test
  fun `chunk object field encodes as chat completion chunk`() {
    val chunk = ChatCompletionChunk(
      id = "chatcmpl-456",
      created = 3000L,
      model = "farol-v1",
      choices = listOf(
        ChunkChoice(delta = Delta(content = "Hello"))
      )
    )
    val encoded = encoder.encodeToString(ChatCompletionChunk.serializer(), chunk)
    assertTrue(encoded.contains("\"object\":\"chat.completion.chunk\""))
  }

  @Test
  fun `chunk choice default index is 0`() {
    val choice = ChunkChoice(delta = Delta(content = "hi"))
    assertEquals(0, choice.index)
  }

  @Test
  fun `chunk choice finish_reason null by default`() {
    val choice = ChunkChoice(delta = Delta(content = "hi"))
    assertNull(choice.finishReason)
  }

  @Test
  fun `chunk delta encodes content`() {
    val chunk = ChatCompletionChunk(
      id = "chatcmpl-789",
      created = 4000L,
      model = "farol-v1",
      choices = listOf(ChunkChoice(delta = Delta(content = "World")))
    )
    val encoded = encoder.encodeToString(ChatCompletionChunk.serializer(), chunk)
    assertTrue(encoded.contains("\"content\":\"World\""))
  }

  // ── ModelsResponse ───────────────────────────────────────────────────────

  @Test
  fun `models response object field encodes as list`() {
    val resp = ModelsResponse(
      data = listOf(ModelInfo(id = "farol-v1"))
    )
    val encoded = encoder.encodeToString(ModelsResponse.serializer(), resp)
    assertTrue(encoded.contains("\"object\":\"list\""))
  }

  @Test
  fun `model info default ownedBy is farol`() {
    val info = ModelInfo(id = "farol-v1")
    assertEquals("farol", info.ownedBy)
  }

  @Test
  fun `model info object field encodes as model`() {
    val info = ModelInfo(id = "farol-v1")
    val encoded = encoder.encodeToString(ModelInfo.serializer(), info)
    assertTrue(encoded.contains("\"object\":\"model\""))
  }

  @Test
  fun `model info owned_by is snake_case in json`() {
    val info = ModelInfo(id = "farol-v1")
    val encoded = encoder.encodeToString(ModelInfo.serializer(), info)
    assertTrue(encoded.contains("\"owned_by\""))
  }

  // ── ErrorResponse ────────────────────────────────────────────────────────

  @Test
  fun `error response encodes message and type`() {
    val err = ErrorResponse(error = ErrorBody(message = "Not found"))
    val encoded = encoder.encodeToString(ErrorResponse.serializer(), err)
    assertTrue(encoded.contains("\"message\":\"Not found\""))
    assertTrue(encoded.contains("\"type\":\"invalid_request_error\""))
  }
}

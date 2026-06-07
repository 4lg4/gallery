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
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
import com.google.ai.edge.gallery.farol.openai.ModelsResponse
import com.google.ai.edge.gallery.farol.openai.OpenAIDecoder
import com.google.ai.edge.gallery.farol.openai.OpenAIJson
import io.ktor.client.request.get
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

private const val API_KEY = "test-api-key"

class RoutesTest {

  // ── /health ──────────────────────────────────────────────────────────────────

  @Test
  fun `health returns 200 without auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/health")
    assertEquals(HttpStatusCode.OK, resp.status)
  }

  @Test
  fun `health body contains status ok and model name`() = testApplication {
    application { farolModule(FakeInferenceEngine(modelName = "test-model"), API_KEY) }
    val resp = client.get("/health")
    val body = resp.bodyAsText()
    assertTrue(body.contains("\"status\":\"ok\""), "body=$body")
    assertTrue(body.contains("\"model\":\"test-model\""), "body=$body")
  }

  // ── /v1/models ───────────────────────────────────────────────────────────────

  @Test
  fun `models returns 401 without auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/v1/models")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  @Test
  fun `models 401 has authentication_error type`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/v1/models")
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("authentication_error", err.error.type)
  }

  @Test
  fun `models returns 200 with bearer auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/v1/models") { header("Authorization", "Bearer $API_KEY") }
    assertEquals(HttpStatusCode.OK, resp.status)
  }

  @Test
  fun `models body has object list and data with fake model id`() = testApplication {
    application { farolModule(FakeInferenceEngine(modelName = "fake-model"), API_KEY) }
    val resp = client.get("/v1/models") { header("Authorization", "Bearer $API_KEY") }
    val body = OpenAIDecoder.decodeFromString<ModelsResponse>(resp.bodyAsText())
    assertEquals("list", body.objectType)
    assertEquals(1, body.data.size)
    assertEquals("fake-model", body.data[0].id)
    assertEquals("farol", body.data[0].ownedBy)
  }

  @Test
  fun `models returns 200 with X-Farol-Key header`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/v1/models") { header("X-Farol-Key", API_KEY) }
    assertEquals(HttpStatusCode.OK, resp.status)
  }

  // ── /v1/chat/completions — auth ───────────────────────────────────────────

  @Test
  fun `chat completions 401 without auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  @Test
  fun `chat completions 401 via X-Farol-Key wrong key`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("X-Farol-Key", "wrong")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  // ── /v1/chat/completions — validation ────────────────────────────────────

  @Test
  fun `chat completions 400 on malformed json`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("{not valid json")
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertNotNull(err.error.type)
    assertTrue(
      err.error.type == "invalid_request_error" || err.error.message.isNotBlank(),
      "expected error shape"
    )
  }

  @Test
  fun `chat completions 400 on empty messages`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[]}""")
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("invalid_request_error", err.error.type)
  }

  // ── /v1/chat/completions — batch happy path ───────────────────────────────

  @Test
  fun `chat completions batch returns full openai shape`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("Hello", ", ", "world"),
      promptTokensOverride = 5,
      completionTokensOverride = 10,
      modelName = "fake-model",
    )
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"model":"fake-model","messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("chat.completion", body.objectType)
    assertTrue(body.id.startsWith("chatcmpl-"), "id=${body.id}")
    assertEquals("fake-model", body.model)
    assertEquals(1, body.choices.size)
    assertEquals("Hello, world", body.choices[0].message.content)
    assertEquals("stop", body.choices[0].finishReason)
    assertEquals(5, body.usage.promptTokens)
    assertEquals(10, body.usage.completionTokens)
    assertEquals(15, body.usage.totalTokens)
  }

  @Test
  fun `chat completions batch passes default maxTokens 1024 to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(1024, fake.lastMaxTokens)
  }

  @Test
  fun `chat completions batch passes temperature to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}],"temperature":0.2}""")
    }
    assertEquals(0.2f, fake.lastTemperature)
  }

  @Test
  fun `chat completions batch accepts blank model field`() = testApplication {
    val fake = FakeInferenceEngine(modelName = "engine-model")
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"model":"","messages":[{"role":"user","content":"hi"}]}""")
    }
    // blank model is accepted; engine.modelName is used in response
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("engine-model", body.model)
  }

  @Test
  fun `chat completions batch passes engine model name when model field omitted`() = testApplication {
    val fake = FakeInferenceEngine(modelName = "engine-model")
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<ChatCompletionResponse>(resp.bodyAsText())
    assertEquals("engine-model", body.model)
  }

  @Test
  fun `chat completions batch 500 on engine throw`() = testApplication {
    val fake = FakeInferenceEngine(throwOnGenerate = RuntimeException("engine exploded"))
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }
    assertEquals(HttpStatusCode.InternalServerError, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("server_error", err.error.type)
  }

  // ── /v1/chat/completions — multimodal ─────────────────────────────────────

  @Test
  fun `chat completions with image part sends image bytes to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    // 1x1 white PNG base64
    val b64Png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    val body = """
      {"messages":[{"role":"user","content":[
        {"type":"text","text":"describe"},
        {"type":"image_url","image_url":{"url":"data:image/png;base64,$b64Png"}}
      ]}]}
    """.trimIndent()
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody(body)
    }
    assertEquals(1, fake.lastImages?.size)
  }
}

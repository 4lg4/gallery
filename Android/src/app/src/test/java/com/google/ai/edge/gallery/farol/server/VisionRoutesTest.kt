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
import com.google.ai.edge.gallery.farol.openai.CaptionResponse
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
import com.google.ai.edge.gallery.farol.openai.OpenAIDecoder
import com.google.ai.edge.gallery.farol.openai.VqaResponse
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val API_KEY = "test-api-key"

/** Tiny 1×1 white PNG — valid image bytes usable in every test. */
private val TINY_PNG: ByteArray = java.util.Base64.getDecoder().decode(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

/**
 * Unit tests for POST /caption and POST /vqa vision endpoints.
 *
 * All tests use [testApplication] so no socket is bound — mirrors the pattern in [RoutesTest].
 */
class VisionRoutesTest {

  // ── /caption — auth ───────────────────────────────────────────────────────

  @Test
  fun `caption 401 without auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/caption") {
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("authentication_error", err.error.type)
  }

  @Test
  fun `caption 401 with wrong key`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/caption") {
      header("X-Farol-Key", "bad-key")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  // ── /vqa — auth ───────────────────────────────────────────────────────────

  @Test
  fun `vqa 401 without auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/vqa") {
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("question", "What is this?")
      }))
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("authentication_error", err.error.type)
  }

  @Test
  fun `vqa 401 with wrong key`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/vqa") {
      header("Authorization", "Bearer wrong-key")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("question", "What is this?")
      }))
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  // ── /caption — happy path ─────────────────────────────────────────────────

  @Test
  fun `caption happy path returns 200 with caption field and fake output`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("A small white square image."),
      modelName = "test-model",
    )
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<CaptionResponse>(resp.bodyAsText())
    assertEquals("A small white square image.", body.caption)
    assertEquals("test-model", body.model)
    assertTrue(body.durationMs >= 0, "durationMs should be non-negative")
  }

  @Test
  fun `caption sends exactly one image to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(1, fake.lastImages?.size, "engine should receive exactly one image")
  }

  @Test
  fun `caption uses default prompt when no prompt field given`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(
      "Describe this image in 2-3 concise sentences.",
      fake.lastPrompt,
      "default prompt mismatch",
    )
  }

  @Test
  fun `caption blank prompt field falls back to default prompt`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("prompt", "   ")  // blank (whitespace-only) — should fall back to default
      }))
    }
    assertEquals(
      "Describe this image in 2-3 concise sentences.",
      fake.lastPrompt,
      "blank prompt field should fall back to the default caption prompt",
    )
  }

  @Test
  fun `caption uses custom prompt when prompt field given`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    val customPrompt = "What colors do you see?"
    client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("prompt", customPrompt)
      }))
    }
    assertEquals(customPrompt, fake.lastPrompt, "custom prompt should be forwarded to engine")
  }

  @Test
  fun `caption passes correct maxTokens and temperature to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(256, fake.lastMaxTokens, "maxTokens should be 256")
    assertEquals(0.3f, fake.lastTemperature, "temperature should be 0.3")
  }

  // ── /caption — error cases ────────────────────────────────────────────────

  @Test
  fun `caption 400 when image part is missing`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      // Send a multipart body with no "image" part — only a dummy field
      setBody(MultiPartFormDataContent(formData {
        append("prompt", "some prompt")
      }))
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("invalid_request_error", err.error.type)
    assertTrue(err.error.message.contains("image"), "message should mention 'image': ${err.error.message}")
  }

  @Test
  fun `caption 500 on engine error`() = testApplication {
    val fake = FakeInferenceEngine(throwOnGenerate = RuntimeException("engine exploded"))
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/caption") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
      }))
    }
    assertEquals(HttpStatusCode.InternalServerError, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("server_error", err.error.type)
  }

  // ── /vqa — happy path ─────────────────────────────────────────────────────

  @Test
  fun `vqa happy path returns 200 with answer field and fake output`() = testApplication {
    val fake = FakeInferenceEngine(
      chunks = listOf("It is a white pixel."),
      modelName = "test-model",
    )
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/vqa") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("question", "What color is the image?")
      }))
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = OpenAIDecoder.decodeFromString<VqaResponse>(resp.bodyAsText())
    assertEquals("It is a white pixel.", body.answer)
    assertEquals("test-model", body.model)
    assertTrue(body.durationMs >= 0, "durationMs should be non-negative")
  }

  @Test
  fun `vqa question is embedded in prompt sent to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    val question = "How many objects are visible?"
    client.post("/vqa") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("question", question)
      }))
    }
    assertNotNull(fake.lastPrompt, "lastPrompt should not be null")
    assertTrue(
      fake.lastPrompt!!.contains(question),
      "question should appear in prompt, got: ${fake.lastPrompt}",
    )
  }

  @Test
  fun `vqa sends exactly one image to engine`() = testApplication {
    val fake = FakeInferenceEngine()
    application { farolModule(fake, API_KEY) }
    client.post("/vqa") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("question", "What do you see?")
      }))
    }
    assertEquals(1, fake.lastImages?.size, "engine should receive exactly one image")
  }

  // ── /vqa — error cases ────────────────────────────────────────────────────

  @Test
  fun `vqa 400 when question field is missing`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/vqa") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        // no "question" field
      }))
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("invalid_request_error", err.error.type)
    assertTrue(err.error.message.contains("question"), "message should mention 'question': ${err.error.message}")
  }

  @Test
  fun `vqa 400 when image part is missing`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.post("/vqa") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("question", "What do you see?")
        // no "image" file part
      }))
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("invalid_request_error", err.error.type)
  }

  @Test
  fun `vqa 500 on engine error`() = testApplication {
    val fake = FakeInferenceEngine(throwOnGenerate = RuntimeException("engine exploded"))
    application { farolModule(fake, API_KEY) }
    val resp = client.post("/vqa") {
      header("Authorization", "Bearer $API_KEY")
      setBody(MultiPartFormDataContent(formData {
        append("image", TINY_PNG, Headers.build {
          append(HttpHeaders.ContentDisposition, "form-data; name=\"image\"; filename=\"img.png\"")
        })
        append("question", "What do you see?")
      }))
    }
    assertEquals(HttpStatusCode.InternalServerError, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("server_error", err.error.type)
  }
}

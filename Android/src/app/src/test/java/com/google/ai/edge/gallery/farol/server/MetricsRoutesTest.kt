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
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
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
import kotlin.test.assertTrue

private const val API_KEY = "test-api-key"

/**
 * Route-level tests for GET /metrics.
 *
 * Metrics wiring choice: [farolModule] accepts an optional [Metrics] instance; route tests pass
 * an explicit instance so they can assert state after making requests.  The inference-call sites
 * in [chatCompletionsRoute], [captionRoute] and [vqaRoute] record via try/finally, ensuring a
 * single record() call per request regardless of success, engine error, or client disconnect.
 *
 * NOTE — cancellation/disconnect path: triggering a real CancellationException inside
 * testApplication is not feasible.  testApplication drives the full coroutine pipeline
 * synchronously; there is no mechanism to cancel the server-side coroutine mid-collect() from the
 * test client side (closing the client connection in a testApplication does not propagate
 * cancellation into the respondTextWriter lambda in the same way a real Netty/CIO disconnect
 * does).  The try/finally correctness is verified by code-review of the production path instead.
 */
class MetricsRoutesTest {

  // ── auth ──────────────────────────────────────────────────────────────────

  @Test
  fun `metrics 401 without auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/metrics")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    val err = OpenAIDecoder.decodeFromString<ErrorResponse>(resp.bodyAsText())
    assertEquals("authentication_error", err.error.type)
  }

  @Test
  fun `metrics 401 with wrong key`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/metrics") { header("X-Farol-Key", "wrong") }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
  }

  @Test
  fun `metrics 200 with bearer auth`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/metrics") { header("Authorization", "Bearer $API_KEY") }
    assertEquals(HttpStatusCode.OK, resp.status)
  }

  @Test
  fun `metrics 200 with X-Farol-Key`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/metrics") { header("X-Farol-Key", API_KEY) }
    assertEquals(HttpStatusCode.OK, resp.status)
  }

  // ── snapshot content ──────────────────────────────────────────────────────

  @Test
  fun `metrics snapshot after one chat call reflects count 1`() = testApplication {
    val metrics = Metrics()
    val fake = FakeInferenceEngine(modelName = "test-model")
    application { farolModule(fake, API_KEY, metrics) }

    // Make one successful chat request
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }

    val resp = client.get("/metrics") { header("Authorization", "Bearer $API_KEY") }
    assertEquals(HttpStatusCode.OK, resp.status)
    val snapshot = OpenAIJson.decodeFromString(MetricsSnapshot.serializer(), resp.bodyAsText())
    assertEquals(1L, snapshot.totalRequests)
    assertEquals(0L, snapshot.totalErrors)
    assertEquals(1L, snapshot.perEndpoint["chat"])
    assertEquals(0L, snapshot.perEndpoint["caption"])
    assertEquals(0L, snapshot.perEndpoint["vqa"])
    assertEquals("test-model", snapshot.model)
  }

  @Test
  fun `metrics snapshot after one failed chat call reflects error count 1`() = testApplication {
    val metrics = Metrics()
    val fake = FakeInferenceEngine(throwOnGenerate = RuntimeException("boom"))
    application { farolModule(fake, API_KEY, metrics) }

    // Fire a request that will cause the engine to throw
    client.post("/v1/chat/completions") {
      header("Authorization", "Bearer $API_KEY")
      contentType(ContentType.Application.Json)
      setBody("""{"messages":[{"role":"user","content":"hi"}]}""")
    }

    val resp = client.get("/metrics") { header("Authorization", "Bearer $API_KEY") }
    val snapshot = OpenAIJson.decodeFromString(MetricsSnapshot.serializer(), resp.bodyAsText())
    assertEquals(1L, snapshot.totalRequests)
    assertEquals(1L, snapshot.totalErrors)
  }

  @Test
  fun `metrics snapshot uptime is non-negative`() = testApplication {
    application { farolModule(FakeInferenceEngine(), API_KEY) }
    val resp = client.get("/metrics") { header("Authorization", "Bearer $API_KEY") }
    val snapshot = OpenAIJson.decodeFromString(MetricsSnapshot.serializer(), resp.bodyAsText())
    assertTrue(snapshot.uptimeMs >= 0L, "uptimeMs=${snapshot.uptimeMs}")
  }
}

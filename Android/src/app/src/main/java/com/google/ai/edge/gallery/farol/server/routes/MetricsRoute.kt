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

package com.google.ai.edge.gallery.farol.server.routes

import com.google.ai.edge.gallery.farol.engine.InferenceEngine
import com.google.ai.edge.gallery.farol.openai.ErrorBody
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
import com.google.ai.edge.gallery.farol.server.Auth
import com.google.ai.edge.gallery.farol.server.Metrics
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * GET /metrics — requires authentication (leaks usage information).
 *
 * Returns a [com.google.ai.edge.gallery.farol.server.MetricsSnapshot] containing uptime,
 * request/error counts per endpoint, last-request timing, and the loaded model name.
 */
fun Routing.metricsRoute(engine: InferenceEngine, apiKey: String, metrics: Metrics) {
  get("/metrics") {
    val auth = call.request.headers["Authorization"]
    val farolKey = call.request.headers["X-Farol-Key"]
    if (!Auth.isAuthorized(auth, farolKey, apiKey)) {
      call.respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(error = ErrorBody(message = "Unauthorized", type = "authentication_error")),
      )
      return@get
    }

    call.respond(HttpStatusCode.OK, metrics.snapshot(model = engine.modelName))
  }
}

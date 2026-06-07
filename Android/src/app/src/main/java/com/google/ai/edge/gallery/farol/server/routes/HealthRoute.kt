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
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * GET /health — no authentication required.
 *
 * Returns `{"status":"ok","model":"<modelName>"}` as a quick liveness probe.
 * Callers can verify both that the server is up and which model is loaded.
 */
fun Routing.healthRoute(engine: InferenceEngine) {
  get("/health") {
    val modelName = engine.modelName
    call.respondText(
      text = """{"status":"ok","model":"$modelName"}""",
      contentType = ContentType.Application.Json,
    )
  }
}

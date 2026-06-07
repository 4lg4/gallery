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
import com.google.ai.edge.gallery.farol.openai.ModelInfo
import com.google.ai.edge.gallery.farol.openai.ModelsResponse
import com.google.ai.edge.gallery.farol.openai.OpenAIJson
import com.google.ai.edge.gallery.farol.server.Auth
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * GET /v1/models — requires authentication.
 *
 * Returns the single loaded model as a standard OpenAI models-list response.
 */
fun Routing.modelsRoute(engine: InferenceEngine, apiKey: String) {
  get("/v1/models") {
    val auth = call.request.headers["Authorization"]
    val farolKey = call.request.headers["X-Farol-Key"]
    if (!Auth.isAuthorized(auth, farolKey, apiKey)) {
      call.respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(error = ErrorBody(message = "Unauthorized", type = "authentication_error")),
      )
      return@get
    }
    call.respond(
      HttpStatusCode.OK,
      ModelsResponse(data = listOf(ModelInfo(id = engine.modelName))),
    )
  }
}

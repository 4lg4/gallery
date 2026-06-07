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

import com.google.ai.edge.gallery.farol.engine.InferenceEngine
import com.google.ai.edge.gallery.farol.openai.ErrorBody
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
import com.google.ai.edge.gallery.farol.openai.OpenAIJson
import com.google.ai.edge.gallery.farol.server.routes.chatCompletionsRoute
import com.google.ai.edge.gallery.farol.server.routes.healthRoute
import com.google.ai.edge.gallery.farol.server.routes.modelsRoute
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException

/**
 * Installs the full Farol OpenAI-compatible module into a Ktor [Application].
 *
 * Exposed as a top-level function so it can be mounted via [testApplication] in unit tests
 * without spinning up a real socket (Task 4 test-host pattern).
 *
 * Error mapping:
 * - [IllegalArgumentException] → 400 `invalid_request_error`
 * - [SerializationException] → 400 `invalid_request_error`
 * - Any other [Throwable] → 500 `server_error`
 */
fun Application.farolModule(engine: InferenceEngine, apiKey: String) {
  install(ContentNegotiation) {
    json(OpenAIJson)
  }

  install(StatusPages) {
    exception<IllegalArgumentException> { call, cause ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          error = ErrorBody(
            message = cause.message ?: "bad request",
            type = "invalid_request_error",
          )
        ),
      )
    }
    exception<SerializationException> { call, cause ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          error = ErrorBody(
            message = cause.message ?: "serialization error",
            type = "invalid_request_error",
          )
        ),
      )
    }
    exception<Throwable> { call, cause ->
      call.respond(
        HttpStatusCode.InternalServerError,
        ErrorResponse(
          error = ErrorBody(
            message = cause.message ?: "internal server error",
            type = "server_error",
          )
        ),
      )
    }
  }

  routing {
    healthRoute(engine)
    modelsRoute(engine, apiKey)
    chatCompletionsRoute(engine, apiKey)
  }
}

/**
 * Starts the Farol embedded HTTP server on [port] (default 8080) bound to all interfaces.
 *
 * Returns the [EmbeddedServer] handle so the caller (Task 5 FarolService) can stop it on
 * lifecycle events.
 */
fun startFarolServer(
  engine: InferenceEngine,
  apiKey: String,
  port: Int = 8080,
): EmbeddedServer<*, *> =
  embeddedServer(CIO, port = port, host = "0.0.0.0") {
    farolModule(engine, apiKey)
  }.start(wait = false)

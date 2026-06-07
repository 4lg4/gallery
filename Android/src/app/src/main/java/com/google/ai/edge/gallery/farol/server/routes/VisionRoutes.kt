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
import com.google.ai.edge.gallery.farol.openai.CaptionResponse
import com.google.ai.edge.gallery.farol.openai.ErrorBody
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
import com.google.ai.edge.gallery.farol.openai.VqaResponse
import com.google.ai.edge.gallery.farol.server.Auth
import com.google.ai.edge.gallery.farol.server.FarolEndpoint
import com.google.ai.edge.gallery.farol.server.Metrics
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray

private const val CAPTION_DEFAULT_PROMPT = "Describe this image in 2-3 concise sentences."
private const val VISION_MAX_TOKENS = 256
private const val VISION_TEMPERATURE = 0.3f

/**
 * Parsed result of a multipart vision request.
 *
 * @property imageBytes Raw bytes of the "image" file part.
 * @property fields Additional form fields keyed by part name.
 */
private data class MultipartVisionRequest(
  val imageBytes: ByteArray?,
  val fields: Map<String, String>,
)

/**
 * Reads a multipart/form-data request and extracts the "image" file bytes plus any
 * additional form fields.  Parts are disposed after reading.
 *
 * The 20 MB Content-Length guard mirrors the pattern used in [chatCompletionsRoute].
 * Chunked bodies without a Content-Length header are trusted (LAN-only server).
 *
 * Returns `null` when a 413 response has already been sent (payload too large), so callers
 * must return immediately without touching the response again.  Non-"image" file parts are
 * intentionally ignored — only the first "image" file part is consumed; additional file parts
 * (e.g. multi-attachment uploads) are read and discarded to drain the multipart stream cleanly.
 */
private suspend fun io.ktor.server.application.ApplicationCall.receiveVisionMultipart(): MultipartVisionRequest? {
  val contentLength = request.contentLength()
  if (contentLength != null && contentLength > 20_000_000L) {
    respond(
      HttpStatusCode.PayloadTooLarge,
      ErrorResponse(error = ErrorBody(message = "Request body exceeds 20 MB limit", type = "invalid_request_error")),
    )
    // Return null to signal that a 413 was already sent — caller must not respond again.
    return null
  }

  var imageBytes: ByteArray? = null
  val fields = mutableMapOf<String, String>()

  val multipart = receiveMultipart()
  multipart.forEachPart { part ->
    when (part) {
      is PartData.FileItem -> {
        // Only the "image" file part is used; all other file parts are intentionally ignored.
        if (part.name == "image") {
          imageBytes = part.provider().toByteArray()
        }
        part.dispose()
      }
      is PartData.FormItem -> {
        part.name?.let { fields[it] = part.value }
        part.dispose()
      }
      else -> part.dispose()
    }
  }

  return MultipartVisionRequest(imageBytes = imageBytes, fields = fields)
}

/**
 * POST /caption — requires authentication.
 *
 * Accepts multipart/form-data with:
 * - "image" (file, required) — raw image bytes (PNG/JPEG).
 * - "prompt" (form field, optional) — override the default caption prompt.
 *
 * Response: [CaptionResponse] with the trimmed generated text, model name and wall-clock
 * duration in milliseconds.
 */
fun Routing.captionRoute(engine: InferenceEngine, apiKey: String, metrics: Metrics) {
  post("/caption") {
    val auth = call.request.headers["Authorization"]
    val farolKey = call.request.headers["X-Farol-Key"]
    if (!Auth.isAuthorized(auth, farolKey, apiKey)) {
      call.respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(error = ErrorBody(message = "Unauthorized", type = "authentication_error")),
      )
      return@post
    }

    val parsed = call.receiveVisionMultipart() ?: return@post  // 413 already sent
    val imageBytes = parsed.imageBytes
      ?: throw IllegalArgumentException("missing required multipart part: image")

    val prompt = parsed.fields["prompt"]?.takeIf { it.isNotBlank() } ?: CAPTION_DEFAULT_PROMPT

    val startMs = System.currentTimeMillis()
    var engineError = false
    try {
      val result = engine.generate(
        prompt = prompt,
        images = listOf(imageBytes),
        maxTokens = VISION_MAX_TOKENS,
        temperature = VISION_TEMPERATURE,
      )
      val durationMs = System.currentTimeMillis() - startMs

      call.respond(
        HttpStatusCode.OK,
        CaptionResponse(
          caption = result.text.trim(),
          model = engine.modelName,
          durationMs = durationMs,
        ),
      )
    } catch (e: Throwable) {
      engineError = true
      throw e
    } finally {
      metrics.record(
        endpoint = FarolEndpoint.CAPTION,
        durationMs = System.currentTimeMillis() - startMs,
        error = engineError,
      )
    }
  }
}

/**
 * POST /vqa — requires authentication.
 *
 * Accepts multipart/form-data with:
 * - "image" (file, required) — raw image bytes (PNG/JPEG).
 * - "question" (form field, required) — the question to answer about the image.
 *
 * Response: [VqaResponse] with the trimmed answer, model name and wall-clock duration in
 * milliseconds.
 */
fun Routing.vqaRoute(engine: InferenceEngine, apiKey: String, metrics: Metrics) {
  post("/vqa") {
    val auth = call.request.headers["Authorization"]
    val farolKey = call.request.headers["X-Farol-Key"]
    if (!Auth.isAuthorized(auth, farolKey, apiKey)) {
      call.respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(error = ErrorBody(message = "Unauthorized", type = "authentication_error")),
      )
      return@post
    }

    val parsed = call.receiveVisionMultipart() ?: return@post  // 413 already sent
    val imageBytes = parsed.imageBytes
      ?: throw IllegalArgumentException("missing required multipart part: image")

    val question = parsed.fields["question"]?.takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("missing required form field: question")

    val prompt = "Answer the question about this image. Be direct and factual.\n\nQuestion: $question"

    val startMs = System.currentTimeMillis()
    var engineError = false
    try {
      val result = engine.generate(
        prompt = prompt,
        images = listOf(imageBytes),
        maxTokens = VISION_MAX_TOKENS,
        temperature = VISION_TEMPERATURE,
      )
      val durationMs = System.currentTimeMillis() - startMs

      call.respond(
        HttpStatusCode.OK,
        VqaResponse(
          answer = result.text.trim(),
          model = engine.modelName,
          durationMs = durationMs,
        ),
      )
    } catch (e: Throwable) {
      engineError = true
      throw e
    } finally {
      metrics.record(
        endpoint = FarolEndpoint.VQA,
        durationMs = System.currentTimeMillis() - startMs,
        error = engineError,
      )
    }
  }
}

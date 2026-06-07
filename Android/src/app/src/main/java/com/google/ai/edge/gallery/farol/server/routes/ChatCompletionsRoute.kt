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
import com.google.ai.edge.gallery.farol.engine.StreamChunk
import com.google.ai.edge.gallery.farol.openai.AssistantMessage
import com.google.ai.edge.gallery.farol.openai.ChatCompletionChunk
import com.google.ai.edge.gallery.farol.openai.ChatCompletionRequest
import com.google.ai.edge.gallery.farol.openai.ChatCompletionResponse
import com.google.ai.edge.gallery.farol.openai.Choice
import com.google.ai.edge.gallery.farol.openai.ChunkChoice
import com.google.ai.edge.gallery.farol.openai.Delta
import com.google.ai.edge.gallery.farol.openai.ErrorBody
import com.google.ai.edge.gallery.farol.openai.ErrorResponse
import com.google.ai.edge.gallery.farol.openai.FunctionCallOut
import com.google.ai.edge.gallery.farol.openai.OpenAIDecoder
import com.google.ai.edge.gallery.farol.openai.OpenAIJson
import com.google.ai.edge.gallery.farol.openai.PromptFlattener
import com.google.ai.edge.gallery.farol.openai.ToolCallOut
import com.google.ai.edge.gallery.farol.openai.ToolCallParser
import com.google.ai.edge.gallery.farol.openai.ToolPromptBuilder
import com.google.ai.edge.gallery.farol.openai.Usage
import com.google.ai.edge.gallery.farol.server.Auth
import com.google.ai.edge.gallery.farol.server.FarolEndpoint
import com.google.ai.edge.gallery.farol.server.Metrics
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DEFAULT_MAX_TOKENS = 1024

/**
 * POST /v1/chat/completions — requires authentication.
 *
 * Supports both batch (stream=false) and streaming (stream=true) modes.
 *
 * Design decisions:
 * - `model` field: a blank or omitted model is silently accepted.  Since Farol is a single-model
 *   server the engine's own [InferenceEngine.modelName] is always used in responses, regardless
 *   of what the client sends.  This matches the behaviour of OpenAI-compatible single-model
 *   servers and avoids spurious 400s from clients that omit the field.
 * - SSE streaming is implemented with [respondTextWriter] rather than the ktor-server-sse plugin
 *   to retain full control over the wire format (exact `data: ...\n\n` event framing) and
 *   simplify testApplication assertions.
 * - Mid-stream engine errors: auth/parse errors are caught before starting the SSE response so
 *   they return proper HTTP status codes.  If the engine throws mid-stream (after the 200 header
 *   is committed) we emit a final `data: {"error":{...}}` event then stop — matching pragmatic
 *   OpenAI-compatible server behaviour.
 * - `max_tokens`: accepted and forwarded to the engine, but capped at engine-init time
 *   (LiteRT-LM `EngineConfig.maxNumTokens`, default 4096).  There is no per-request cap at the
 *   Ktor layer.  A response header `X-Farol-MaxTokens: engine-cap` signals this to callers.
 */
fun Routing.chatCompletionsRoute(engine: InferenceEngine, apiKey: String, metrics: Metrics) {
  post("/v1/chat/completions") {
    // ── Auth ──────────────────────────────────────────────────────────────────
    val auth = call.request.headers["Authorization"]
    val farolKey = call.request.headers["X-Farol-Key"]
    if (!Auth.isAuthorized(auth, farolKey, apiKey)) {
      call.respond(
        HttpStatusCode.Unauthorized,
        ErrorResponse(error = ErrorBody(message = "Unauthorized", type = "authentication_error")),
      )
      return@post
    }

    // ── Body size cap ─────────────────────────────────────────────────────────
    // CIO 3.4.3 has no maxRequestBodySize in Configuration, so we guard via
    // Content-Length.  Chunked bodies without a Content-Length header are
    // trusted (LAN-only server); they are bounded by the engine's own read
    // timeout rather than a byte cap.
    val contentLength = call.request.contentLength()
    if (contentLength != null && contentLength > 20_000_000L) {
      call.respond(
        HttpStatusCode.PayloadTooLarge,
        ErrorResponse(error = ErrorBody(message = "Request body exceeds 20 MB limit", type = "invalid_request_error")),
      )
      return@post
    }

    // ── Parse ─────────────────────────────────────────────────────────────────
    val rawBody = call.receiveText()
    val request: ChatCompletionRequest = try {
      OpenAIDecoder.decodeFromString(rawBody)
    } catch (e: Exception) {
      throw IllegalArgumentException("malformed request body: ${e.message}", e)
    }

    // ── Validate ──────────────────────────────────────────────────────────────
    if (request.messages.isEmpty()) {
      throw IllegalArgumentException("messages must not be empty")
    }

    // ── Flatten ──────────────────────────────────────────────────────────────
    val flat = PromptFlattener.flatten(request.messages)
    val maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS
    val temperature = request.temperature?.toFloat()

    val responseId = "chatcmpl-${UUID.randomUUID()}"
    val created = Instant.now().epochSecond
    val modelName = engine.modelName

    // Honesty signal: max_tokens is accepted but capped at engine-init time, not per-request.
    if (request.maxTokens != null) {
      call.response.headers.append("X-Farol-MaxTokens", "engine-cap")
    }

    // ── Tools: determine if tool folding is active ────────────────────────────
    // toolChoice="none" suppresses folding; anything else (including absent / "auto") enables it.
    val toolChoiceNone = (request.toolChoice as? JsonPrimitive)?.contentOrNull == "none"
    val activeTools = request.tools?.takeIf { it.isNotEmpty() && !toolChoiceNone }

    // ── Compose systemInstruction ─────────────────────────────────────────────
    // system messages from the conversation + optional tool prompt.
    val systemInstruction: String? = buildString {
      if (flat.systemText.isNotEmpty()) append(flat.systemText)
      if (activeTools != null) {
        if (isNotEmpty()) append("\n\n")
        append(ToolPromptBuilder.build(activeTools))
      }
    }.takeIf { it.isNotEmpty() }

    // ── Stream or Batch ───────────────────────────────────────────────────────
    if (request.stream) {
      if (activeTools != null) {
        // Tool-enabled stream: buffer the full generation, parse, then emit as SSE.
        call.respondSSETooled(engine, flat, maxTokens, temperature, request.thinking, systemInstruction, responseId, created, modelName, metrics)
      } else {
        call.respondSSE(engine, flat, maxTokens, temperature, request.thinking, systemInstruction, responseId, created, modelName, metrics)
      }
    } else {
      if (activeTools != null) {
        // Tool-enabled batch: collect, parse, emit tool_calls or normal response.
        val startMs = System.currentTimeMillis()
        var engineError = false
        try {
          val result = engine.generate(flat.promptText, flat.images, maxTokens, temperature, request.thinking, systemInstruction)
          val parsed = ToolCallParser.parse(result.text)
          val (message, finishReason) = when (parsed) {
            is ToolCallParser.ParseResult.ToolCalls -> {
              val toolCallOuts = parsed.calls.mapIndexed { idx, tc ->
                ToolCallOut(
                  id = generateCallId(idx),
                  function = FunctionCallOut(
                    name = tc.name,
                    arguments = tc.argumentsJson,
                  ),
                )
              }
              val contentText = parsed.prefixText.takeIf { it.isNotBlank() }
              AssistantMessage(
                content = contentText,
                toolCalls = toolCallOuts,
              ) to "tool_calls"
            }
            is ToolCallParser.ParseResult.NoToolCall -> {
              AssistantMessage(
                content = parsed.text,
                reasoningContent = result.reasoningText,
              ) to "stop"
            }
          }
          call.respond(
            HttpStatusCode.OK,
            ChatCompletionResponse(
              id = responseId,
              created = created,
              model = modelName,
              choices = listOf(Choice(message = message, finishReason = finishReason)),
              usage = Usage(
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                totalTokens = result.promptTokens + result.completionTokens,
              ),
            ),
          )
        } catch (e: Throwable) {
          engineError = true
          throw e
        } finally {
          metrics.record(
            endpoint = FarolEndpoint.CHAT,
            durationMs = System.currentTimeMillis() - startMs,
            error = engineError,
          )
        }
      } else {
        val startMs = System.currentTimeMillis()
        var engineError = false
        try {
          val result = engine.generate(flat.promptText, flat.images, maxTokens, temperature, request.thinking, systemInstruction)
          call.respond(
            HttpStatusCode.OK,
            ChatCompletionResponse(
              id = responseId,
              created = created,
              model = modelName,
              choices = listOf(
                Choice(
                  message = AssistantMessage(
                    content = result.text,
                    reasoningContent = result.reasoningText,
                  ),
                  finishReason = "stop",
                )
              ),
              usage = Usage(
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                totalTokens = result.promptTokens + result.completionTokens,
              ),
            ),
          )
        } catch (e: Throwable) {
          engineError = true
          throw e
        } finally {
          metrics.record(
            endpoint = FarolEndpoint.CHAT,
            durationMs = System.currentTimeMillis() - startMs,
            error = engineError,
          )
        }
      }
    }
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSSE(
  engine: InferenceEngine,
  flat: PromptFlattener.FlatPrompt,
  maxTokens: Int,
  temperature: Float?,
  thinking: Boolean,
  systemInstruction: String?,
  responseId: String,
  created: Long,
  modelName: String,
  metrics: Metrics,
) {
  val startMs = System.currentTimeMillis()
  response.headers.append("Cache-Control", "no-cache")
  response.headers.append("Connection", "keep-alive")
  respondTextWriter(
    contentType = ContentType.parse("text/event-stream; charset=utf-8"),
    status = HttpStatusCode.OK,
  ) {
    fun sendChunk(chunk: ChatCompletionChunk) {
      val json = OpenAIJson.encodeToString(ChatCompletionChunk.serializer(), chunk)
      write("data: $json\n\n")
      flush()
    }

    fun makeChunk(delta: Delta, finishReason: String? = null) = ChatCompletionChunk(
      id = responseId,
      created = created,
      model = modelName,
      choices = listOf(ChunkChoice(delta = delta, finishReason = finishReason)),
    )

    // First chunk: role signal — content is null (not "") to match real OpenAI
    // mid-stream wire format where the role-only chunk carries "content":null.
    sendChunk(makeChunk(Delta(role = "assistant", content = null)))

    var streamError: Throwable? = null

    // try/finally ensures metrics.record() runs even when the coroutine is
    // cancelled by a client disconnect.  CancellationException is caught by
    // the .catch operator below, stored as streamError so the record() in
    // finally marks it as an error, then re-thrown so the coroutine machinery
    // can clean up correctly.
    try {
      engine.generateStream(flat.promptText, flat.images, maxTokens, temperature, thinking, systemInstruction)
        .onEach { chunk: StreamChunk ->
          when {
            chunk.thought != null ->
              // Emit a reasoning_content delta; content is null for thought chunks.
              sendChunk(makeChunk(Delta(reasoningContent = chunk.thought)))
            chunk.content != null ->
              sendChunk(makeChunk(Delta(content = chunk.content)))
            // All-null chunk (should not occur in practice) — skip silently.
          }
        }
        .catch { e ->
          streamError = e
        }
        .collect()

      if (streamError != null) {
        // Mid-stream engine error: emit error event then stop (header already committed)
        val errJson = buildJsonObject {
          put("error", buildJsonObject {
            put("message", streamError!!.message ?: "unknown error")
            put("type", "server_error")
          })
        }.toString()
        write("data: $errJson\n\n")
        flush()
      } else {
        // Normal completion: final chunk with finish_reason=stop
        sendChunk(makeChunk(Delta(content = null), finishReason = "stop"))
      }

      // Always terminate with [DONE]
      write("data: [DONE]\n\n")
      flush()
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Client disconnected mid-stream; mark as error for metrics then re-throw
      // so the coroutine structured-concurrency machinery can propagate cancellation.
      streamError = e
      throw e
    } finally {
      metrics.record(
        endpoint = FarolEndpoint.CHAT,
        durationMs = System.currentTimeMillis() - startMs,
        error = streamError != null,
      )
    }
  }
}

/**
 * SSE response for tool-enabled requests.
 *
 * Buffers the full generation, runs [ToolCallParser], then emits the result as SSE.
 * Deliberate v1 design: tool requests do not stream incrementally (the client needs the full
 * tool_call block to execute the tool anyway; incremental tokens are meaningless here).
 *
 * SSE shape when tool_calls detected:
 *   1. role chunk (assistant)
 *   2. tool_calls delta chunk
 *   3. finish_reason="tool_calls" chunk
 *   4. [DONE]
 *
 * SSE shape when plain answer:
 *   1. role chunk
 *   2. content chunks (split arbitrarily into segments)
 *   3. finish_reason="stop" chunk
 *   4. [DONE]
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondSSETooled(
  engine: InferenceEngine,
  flat: PromptFlattener.FlatPrompt,
  maxTokens: Int,
  temperature: Float?,
  thinking: Boolean,
  systemInstruction: String?,
  responseId: String,
  created: Long,
  modelName: String,
  metrics: Metrics,
) {
  val startMs = System.currentTimeMillis()
  response.headers.append("Cache-Control", "no-cache")
  response.headers.append("Connection", "keep-alive")

  // Collect full generation before opening the SSE writer.
  var engineError = false
  val result = try {
    engine.generate(flat.promptText, flat.images, maxTokens, temperature, thinking, systemInstruction)
  } catch (e: Throwable) {
    engineError = true
    metrics.record(endpoint = FarolEndpoint.CHAT, durationMs = System.currentTimeMillis() - startMs, error = true)
    throw e
  }

  val parsed = ToolCallParser.parse(result.text)

  try {
    respondTextWriter(
      contentType = ContentType.parse("text/event-stream; charset=utf-8"),
      status = HttpStatusCode.OK,
    ) {
      fun sendChunk(chunk: ChatCompletionChunk) {
        val json = OpenAIJson.encodeToString(ChatCompletionChunk.serializer(), chunk)
        write("data: $json\n\n")
        flush()
      }
      fun makeChunk(delta: Delta, finishReason: String? = null) = ChatCompletionChunk(
        id = responseId,
        created = created,
        model = modelName,
        choices = listOf(ChunkChoice(delta = delta, finishReason = finishReason)),
      )

      // Role chunk.
      sendChunk(makeChunk(Delta(role = "assistant")))

      when (parsed) {
        is ToolCallParser.ParseResult.ToolCalls -> {
          // index is set on streaming deltas so openai-python accumulator can key on it.
          val toolCallOuts = parsed.calls.mapIndexed { idx, tc ->
            ToolCallOut(
              id = generateCallId(idx),
              index = idx,
              function = FunctionCallOut(name = tc.name, arguments = tc.argumentsJson),
            )
          }
          sendChunk(makeChunk(Delta(toolCalls = toolCallOuts)))
          sendChunk(makeChunk(Delta(), finishReason = "tool_calls"))
        }
        is ToolCallParser.ParseResult.NoToolCall -> {
          if (parsed.text.isNotEmpty()) {
            sendChunk(makeChunk(Delta(content = parsed.text)))
          }
          sendChunk(makeChunk(Delta(content = null), finishReason = "stop"))
        }
      }

      write("data: [DONE]\n\n")
      flush()
    }
  } catch (e: kotlinx.coroutines.CancellationException) {
    engineError = true
    throw e
  } finally {
    metrics.record(
      endpoint = FarolEndpoint.CHAT,
      durationMs = System.currentTimeMillis() - startMs,
      error = engineError,
    )
  }
}

/**
 * Generates a unique, determinism-friendly tool call ID.
 *
 * Format: `call_<8 hex chars><index>` — the UUID-derived hex prefix ensures uniqueness across
 * multiple independent invocations; the decimal index suffix disambiguates sibling calls within
 * the *same* response (e.g. "call_0a1b2c3d0", "call_0a1b2c3d1").
 */
private fun generateCallId(index: Int): String {
  val rand = UUID.randomUUID().toString().replace("-", "").take(8)
  return "call_$rand$index"
}

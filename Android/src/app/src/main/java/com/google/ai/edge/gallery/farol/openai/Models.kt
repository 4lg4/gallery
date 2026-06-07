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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ── Shared codec instances ────────────────────────────────────────────────────
// Use these everywhere so codec configuration cannot drift between routes.

/** Encoder for outgoing OpenAI-compatible responses.  encodeDefaults=true ensures
 *  fields such as "object" and "finish_reason" are always present on the wire. */
val OpenAIJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Decoder for incoming client requests.  ignoreUnknownKeys=true tolerates future
 *  OpenAI fields we don't yet handle. */
val OpenAIDecoder = Json { ignoreUnknownKeys = true }

// ── Request ──────────────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionRequest(
  val model: String = "",
  val messages: List<ChatMessage>,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  val temperature: Double? = null,
  val stream: Boolean = false,
)

@Serializable
data class ChatMessage(
  val role: String,
  val content: JsonElement,  // string OR array of parts
)

// ── Response ─────────────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionResponse(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<Choice>,
  val usage: Usage,
)

@Serializable
data class Choice(
  val index: Int = 0,
  val message: AssistantMessage,
  @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class AssistantMessage(
  val role: String = "assistant",
  val content: String,
)

@Serializable
data class Usage(
  @SerialName("prompt_tokens") val promptTokens: Int,
  @SerialName("completion_tokens") val completionTokens: Int,
  @SerialName("total_tokens") val totalTokens: Int,
)

// ── Streaming chunk ───────────────────────────────────────────────────────────

@Serializable
data class ChatCompletionChunk(
  val id: String,
  @SerialName("object") val objectType: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<ChunkChoice>,
)

@Serializable
data class ChunkChoice(
  val index: Int = 0,
  val delta: Delta,
  @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
  val role: String? = null,
  val content: String? = null,
)

// ── Models list ───────────────────────────────────────────────────────────────

@Serializable
data class ModelsResponse(
  @SerialName("object") val objectType: String = "list",
  val data: List<ModelInfo>,
)

@Serializable
data class ModelInfo(
  val id: String,
  @SerialName("object") val objectType: String = "model",
  val created: Long = 0,
  @SerialName("owned_by") val ownedBy: String = "farol",
)

// ── Error ─────────────────────────────────────────────────────────────────────

@Serializable
data class ErrorResponse(
  val error: ErrorBody,
)

@Serializable
data class ErrorBody(
  val message: String,
  val type: String = "invalid_request_error",
  // TODO(v2): add `param: String? = null` and `code: String? = null` for OpenAI Python SDK
  //  error-parse compatibility (openai.BadRequestError.param / .code).
)

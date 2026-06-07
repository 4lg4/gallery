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
import kotlinx.serialization.json.JsonObject

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
  /**
   * Non-standard extension field.  When true, the server enables Gemma 4 thinking mode and
   * surfaces the reasoning text in [AssistantMessage.reasoningContent] (batch) or
   * [Delta.reasoningContent] (streaming).
   *
   * Strict OpenAI clients that do not send this field will receive the default value (false) and
   * behave identically to before this field existed.
   */
  val thinking: Boolean = false,
  /**
   * OpenAI tool definitions.  When non-empty and [toolChoice] is not "none", the tool descriptions
   * are folded into the system instruction as text and the model is instructed to emit a delimited
   * JSON block for calls.
   */
  val tools: List<ToolDef>? = null,
  /**
   * Controls tool selection.  May be the string "auto", "none", "required", or a
   * `{"type":"function","function":{"name":"..."}}` object.  Kept as [JsonElement] so unknown
   * shapes are tolerated without 400s.  Only "none" suppresses tool folding; everything else
   * enables it when [tools] is non-empty.
   */
  @SerialName("tool_choice") val toolChoice: JsonElement? = null,
)

// ── Tool definitions (request) ────────────────────────────────────────────────

@Serializable
data class ToolDef(
  val type: String = "function",
  val function: FunctionDef,
)

@Serializable
data class FunctionDef(
  val name: String,
  val description: String? = null,
  val parameters: JsonObject? = null,
)

// ── Tool call output (response / delta) ───────────────────────────────────────

@Serializable
data class ToolCallOut(
  val id: String,
  val type: String = "function",
  val function: FunctionCallOut,
)

@Serializable
data class FunctionCallOut(
  val name: String,
  /** Arguments as a compact JSON STRING (per OpenAI spec), e.g. "{\"city\":\"Perth\"}". */
  val arguments: String,
)

@Serializable
data class ChatMessage(
  val role: String,
  val content: JsonElement,  // string OR array of parts
  /**
   * Passthrough: tool_call_id present on role="tool" messages.  The flattener renders these as
   * "[tool <id> result]: <content>".  Null for all other message roles.
   */
  @SerialName("tool_call_id") val toolCallId: String? = null,
  /**
   * Passthrough: tool_calls present on role="assistant" messages that contain a prior tool call.
   * Kept as [JsonElement] to avoid coupling to [ToolCallOut] in the message layer — the flattener
   * renders them as text without needing a fully-typed decode.  Null when not present.
   */
  @SerialName("tool_calls") val toolCalls: JsonElement? = null,
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
  /**
   * Assistant text content.  Null when this message carries [toolCalls] only (per OpenAI spec).
   * When both [content] and [toolCalls] are present, [content] holds any prefix prose the model
   * emitted before the tool_call block.
   */
  val content: String? = null,
  /**
   * Accumulated reasoning/thinking text, following the DeepSeek convention.
   * Null when thinking was not requested or produced no output.
   */
  @SerialName("reasoning_content") val reasoningContent: String? = null,
  /**
   * Tool calls emitted by the model.  Non-null only when the model produced a
   * tool_call block in its response (finish_reason = "tool_calls").
   */
  @SerialName("tool_calls") val toolCalls: List<ToolCallOut>? = null,
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
  /**
   * Incremental reasoning/thinking token, following the DeepSeek convention.
   * Null on all non-thinking chunks and when thinking=false was requested.
   */
  @SerialName("reasoning_content") val reasoningContent: String? = null,
  /**
   * Tool calls delta.  When non-null, the entire tool_calls list is present in a single delta
   * chunk (FAROL v1 buffers tool results, so there are no partial tool_call deltas).
   */
  @SerialName("tool_calls") val toolCalls: List<ToolCallOut>? = null,
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

// ── Vision convenience responses ─────────────────────────────────────────────

@Serializable
data class CaptionResponse(
  val caption: String,
  val model: String,
  @SerialName("durationMs") val durationMs: Long,
)

@Serializable
data class VqaResponse(
  val answer: String,
  val model: String,
  @SerialName("durationMs") val durationMs: Long,
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

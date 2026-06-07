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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a model's full text output to detect `\`\`\`tool_call` fenced blocks.
 *
 * Two detection modes:
 * 1. Fenced block: ` ```tool_call\n{...}\n``` ` — the canonical form the model is instructed
 *    to produce.  Multiple blocks are supported.  The block(s) are stripped from the text and the
 *    remaining prose becomes [ParseResult.ToolCalls.prefixText].
 * 2. Bare JSON: when the ENTIRE trimmed response is a JSON object with exactly the keys "name" and
 *    "arguments" (and no other top-level keys) — tolerated as a fallback for models that skip the
 *    fence.
 *
 * Malformed JSON inside a detected block → [ParseResult.NoToolCall] with the original text
 * unchanged.  This ensures callers never receive a 500 from a parse error.
 */
object ToolCallParser {

  /** Codec for parsing tool-call JSON from the model output. */
  private val lenient = Json { ignoreUnknownKeys = false }

  private val FENCE_REGEX = Regex(
    """```tool_call\s*\n(.*?)\n```""",
    setOf(RegexOption.DOT_MATCHES_ALL),
  )

  /** Represents a single parsed tool call. */
  data class ToolCall(
    val name: String,
    /** Arguments as a compact JSON STRING (per OpenAI spec). */
    val argumentsJson: String,
  )

  /** Result of [parse]. */
  sealed class ParseResult {
    /** No tool-call block detected; [text] is the original model output unchanged. */
    data class NoToolCall(val text: String) : ParseResult()

    /**
     * One or more tool-call blocks detected.
     *
     * @property calls Parsed tool calls in encounter order.
     * @property prefixText Text with the tool_call blocks removed.  May be blank.
     */
    data class ToolCalls(
      val calls: List<ToolCall>,
      val prefixText: String,
    ) : ParseResult()
  }

  /**
   * Parses [fullText] for tool-call blocks.
   *
   * Returns [ParseResult.NoToolCall] when:
   * - No `\`\`\`tool_call` fence is found AND the text is not a bare tool-call JSON object.
   * - A fence is found but its JSON payload is malformed.
   *
   * Returns [ParseResult.ToolCalls] when one or more valid tool-call blocks are found.
   */
  fun parse(fullText: String): ParseResult {
    // ── Mode 1: fenced blocks ─────────────────────────────────────────────────
    val matches = FENCE_REGEX.findAll(fullText).toList()
    if (matches.isNotEmpty()) {
      val calls = mutableListOf<ToolCall>()
      for (match in matches) {
        val json = match.groupValues[1].trim()
        val call = parseToolCallJson(json) ?: return ParseResult.NoToolCall(fullText)
        calls.add(call)
      }
      // Strip all fence blocks from the text to get prefix prose.
      val stripped = FENCE_REGEX.replace(fullText, "").trim()
      return ParseResult.ToolCalls(calls = calls, prefixText = stripped)
    }

    // ── Mode 2: bare JSON object (entire response) ────────────────────────────
    val trimmed = fullText.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      val call = parseToolCallJson(trimmed)
      if (call != null) {
        return ParseResult.ToolCalls(calls = listOf(call), prefixText = "")
      }
    }

    return ParseResult.NoToolCall(fullText)
  }

  /**
   * Parses a single tool-call JSON string into a [ToolCall].
   *
   * Returns null when:
   * - JSON is malformed.
   * - Required "name" key is missing.
   * - "arguments" key is missing.
   */
  private fun parseToolCallJson(json: String): ToolCall? {
    val obj: JsonObject = try {
      lenient.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
      return null
    }

    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val argsElement = obj["arguments"] ?: return null

    // Normalize arguments to a compact JSON string (per OpenAI spec the field is always a string).
    val argsJson = when {
      argsElement is kotlinx.serialization.json.JsonPrimitive && argsElement.isString ->
        // Already a JSON string in the model output — pass through.
        argsElement.content
      else ->
        // Model emitted an inline object — compact-encode it to a string.
        Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), argsElement)
    }

    return ToolCall(name = name, argumentsJson = argsJson)
  }
}

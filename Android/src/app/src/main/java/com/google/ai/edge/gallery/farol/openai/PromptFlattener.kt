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

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts a list of [ChatMessage]s into a flat text prompt and a list of decoded image bytes.
 *
 * Content shapes accepted:
 * - String primitive → taken as-is.
 * - Array of parts:
 *   - `{ "type": "text", "text": "..." }` → collected as text segment.
 *   - `{ "type": "image_url", "image_url": { "url": "data:..." } }` → base64 decoded.
 *     Non-data: URLs are rejected with [IllegalArgumentException].
 *   - Any other type → [IllegalArgumentException].
 * - Any other JSON shape → [IllegalArgumentException].
 *
 * Text segments (across all messages) are joined with "\n\n".
 *
 * Special message roles:
 * - `role="system"` messages are excluded from [FlatPrompt.promptText] and collected separately
 *   via [extractSystemText] — the route folds them into [systemInstruction].
 * - `role="assistant"` messages that carry `tool_calls` are rendered as text:
 *   `[assistant called tool <name> with arguments <json>]`.
 * - `role="tool"` messages are rendered as: `[tool <tool_call_id> result]: <content>`.
 */
object PromptFlattener {

  /**
   * Holds the result of flattening a list of [ChatMessage]s.
   *
   * @property promptText All non-system text segments from all messages joined with "\n\n".
   * @property images Raw bytes of every image part, in the order they appeared.
   * @property systemText System-role message contents joined with "\n\n", or empty string if none.
   *
   * **Equality caveat:** [FlatPrompt] is a data class, but [List.equals] on
   * [List]<[ByteArray]> uses **reference equality** for each [ByteArray] element,
   * not content equality.  Two [FlatPrompt] instances built from identical input
   * will NOT be `==` if they hold different [ByteArray] objects.  Use
   * [assertContentEquals] (in tests) or compare element-by-element in production code.
   */
  data class FlatPrompt(
    val promptText: String,
    val images: List<ByteArray>,
    val systemText: String = "",
  )

  /**
   * Converts [messages] into a [FlatPrompt].
   *
   * System messages are separated into [FlatPrompt.systemText] rather than mixed into
   * [FlatPrompt.promptText].  The route uses [systemText] (plus any tool prompt) as the
   * [systemInstruction] parameter to [InferenceEngine.generate] / [generateStream].
   *
   * Tool-role and assistant-with-tool_calls messages are rendered as bracketed text so that
   * multi-turn tool-call conversations round-trip correctly through the flat prompt.
   */
  fun flatten(messages: List<ChatMessage>): FlatPrompt {
    val textSegments = mutableListOf<String>()
    val systemSegments = mutableListOf<String>()
    val images = mutableListOf<ByteArray>()

    for (message in messages) {
      // ── role="tool": render as "[tool <id> result]: <content>" ─────────────
      if (message.role == "tool") {
        val id = message.toolCallId ?: "unknown"
        val resultText = when (val c = message.content) {
          is JsonPrimitive -> if (c.isString) c.content else c.toString()
          else -> c.toString()
        }
        textSegments.add("[tool $id result]: $resultText")
        continue
      }

      // ── role="assistant" with tool_calls: render each call as text ─────────
      if (message.role == "assistant" && message.toolCalls != null) {
        val callsArray = try {
          message.toolCalls.jsonArray
        } catch (_: Exception) {
          null
        }
        if (callsArray != null) {
          for (callEl in callsArray) {
            val callObj = try { callEl.jsonObject } catch (_: Exception) { null } ?: continue
            val name = callObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
              ?: callObj["function"]?.jsonObject?.get("name")?.toString()
              ?: "unknown"
            val args = callObj["function"]?.jsonObject?.get("arguments")?.let {
              if (it is JsonPrimitive && it.isString) it.content else it.toString()
            } ?: "{}"
            textSegments.add("[assistant called tool $name with arguments $args]")
          }
        }
        // Also include any text content the assistant emitted alongside tool_calls
        val textContent = when (val c = message.content) {
          is JsonPrimitive -> if (c.isString && c.content.isNotEmpty()) c.content else null
          else -> null
        }
        if (textContent != null) textSegments.add(textContent)
        continue
      }

      // ── role="system": collect separately ─────────────────────────────────
      if (message.role == "system") {
        val sysText = when (val c = message.content) {
          is JsonPrimitive -> if (c.isString) c.content else throw IllegalArgumentException(
            "ChatMessage content primitive must be a string, got: $c"
          )
          else -> throw IllegalArgumentException(
            "System message content must be a string, got: ${message.content::class.simpleName}"
          )
        }
        systemSegments.add(sysText)
        continue
      }

      // ── Normal message (user / assistant without tool_calls / other) ───────
      when (val content = message.content) {
        is JsonPrimitive -> {
          if (!content.isString) {
            throw IllegalArgumentException(
              "ChatMessage content primitive must be a string, got: $content"
            )
          }
          textSegments.add(content.content)
        }
        is JsonArray -> {
          for (part in content) {
            val obj = part.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
              ?: throw IllegalArgumentException("Part missing 'type' field: $part")
            when (type) {
              "text" -> {
                val text = obj["text"]?.jsonPrimitive?.content
                  ?: throw IllegalArgumentException("Text part missing 'text' field: $part")
                textSegments.add(text)
              }
              "image_url" -> {
                val imageUrlObj = obj["image_url"]?.jsonObject
                  ?: throw IllegalArgumentException(
                    "image_url part missing 'image_url' object: $part"
                  )
                val url = imageUrlObj["url"]?.jsonPrimitive?.content
                  ?: throw IllegalArgumentException(
                    "image_url object missing 'url' field: $part"
                  )
                if (!url.startsWith("data:")) {
                  throw IllegalArgumentException(
                    "Only data: URLs are accepted for image_url parts, got: $url"
                  )
                }
                val commaIdx = url.indexOf(',')
                if (commaIdx < 0) {
                  throw IllegalArgumentException("Malformed data URL (no comma): $url")
                }
                val b64 = url.substring(commaIdx + 1)
                val decoded = try {
                  Base64.getDecoder().decode(b64)
                } catch (e: IllegalArgumentException) {
                  throw IllegalArgumentException("Malformed base64 in data URL: $url", e)
                }
                images.add(decoded)
              }
              else -> throw IllegalArgumentException("Unknown part type: $type")
            }
          }
        }
        else -> throw IllegalArgumentException(
          "ChatMessage content must be a string or array, got: ${content::class.simpleName}"
        )
      }
    }

    return FlatPrompt(
      promptText = textSegments.joinToString("\n\n"),
      images = images,
      systemText = systemSegments.joinToString("\n\n"),
    )
  }
}

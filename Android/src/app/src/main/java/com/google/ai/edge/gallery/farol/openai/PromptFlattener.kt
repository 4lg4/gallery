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
 */
object PromptFlattener {

  data class FlatPrompt(val promptText: String, val images: List<ByteArray>)

  fun flatten(messages: List<ChatMessage>): FlatPrompt {
    val textSegments = mutableListOf<String>()
    val images = mutableListOf<ByteArray>()

    for (message in messages) {
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
                images.add(Base64.getDecoder().decode(b64))
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
    )
  }
}

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

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptFlattenerTest {

  // A tiny 3-byte PNG header encoded in base64 (not a real PNG, just 3 bytes: 0x89, 0x50, 0x4E)
  private val TINY_BASE64 = "iVBO"
  private val TINY_BYTES = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte())

  @Test
  fun `single user message with string content is returned as-is`() {
    val messages = listOf(
      ChatMessage(role = "user", content = JsonPrimitive("hello world"))
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("hello world", result.promptText)
    assertEquals(0, result.images.size)
  }

  @Test
  fun `multi-turn messages with string content are joined with double newline`() {
    val messages = listOf(
      ChatMessage(role = "user", content = JsonPrimitive("a")),
      ChatMessage(role = "assistant", content = JsonPrimitive("b")),
      ChatMessage(role = "user", content = JsonPrimitive("c")),
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("a\n\nb\n\nc", result.promptText)
    assertEquals(0, result.images.size)
  }

  @Test
  fun `system and user messages joined with double newline`() {
    val messages = listOf(
      ChatMessage(role = "system", content = JsonPrimitive("You are helpful.")),
      ChatMessage(role = "user", content = JsonPrimitive("Tell me a joke.")),
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("You are helpful.\n\nTell me a joke.", result.promptText)
  }

  @Test
  fun `parts array with text parts only is joined`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "text")
        put("text", "Hello")
      })
      add(buildJsonObject {
        put("type", "text")
        put("text", "World")
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    val result = PromptFlattener.flatten(messages)
    assertEquals("Hello\n\nWorld", result.promptText)
    assertEquals(0, result.images.size)
  }

  @Test
  fun `data image url part is base64 decoded to bytes`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "data:image/png;base64,$TINY_BASE64")
        })
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    val result = PromptFlattener.flatten(messages)
    assertEquals(1, result.images.size)
    assertContentEquals(TINY_BYTES, result.images[0])
  }

  @Test
  fun `http image url is rejected with IllegalArgumentException`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "http://example.com/image.png")
        })
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
  }

  @Test
  fun `https image url is rejected with IllegalArgumentException`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "https://example.com/image.png")
        })
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
  }

  @Test
  fun `unknown part type is rejected with IllegalArgumentException`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "audio")
        put("data", "somebytes")
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
  }

  @Test
  fun `non-string non-array content shape is rejected`() {
    val obj = buildJsonObject { put("key", "value") }
    val messages = listOf(ChatMessage(role = "user", content = obj))
    assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
  }

  @Test
  fun `mixed text and image parts in one message`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "text")
        put("text", "Describe this image:")
      })
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "data:image/png;base64,$TINY_BASE64")
        })
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    val result = PromptFlattener.flatten(messages)
    assertEquals("Describe this image:", result.promptText)
    assertEquals(1, result.images.size)
    assertContentEquals(TINY_BYTES, result.images[0])
  }
}

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

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptFlattenerTest {

  // First 3 bytes of the PNG magic number (0x89, 0x50, 0x4E) encoded in base64 — not a real PNG.
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
  fun `system message is extracted to systemText not promptText`() {
    val messages = listOf(
      ChatMessage(role = "system", content = JsonPrimitive("You are helpful.")),
      ChatMessage(role = "user", content = JsonPrimitive("Tell me a joke.")),
    )
    val result = PromptFlattener.flatten(messages)
    // System messages go to systemText; only user/assistant messages go to promptText.
    assertEquals("You are helpful.", result.systemText)
    assertEquals("Tell me a joke.", result.promptText)
  }

  @Test
  fun `multiple system messages joined in systemText`() {
    val messages = listOf(
      ChatMessage(role = "system", content = JsonPrimitive("Instruction 1.")),
      ChatMessage(role = "system", content = JsonPrimitive("Instruction 2.")),
      ChatMessage(role = "user", content = JsonPrimitive("Hi")),
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("Instruction 1.\n\nInstruction 2.", result.systemText)
    assertEquals("Hi", result.promptText)
  }

  @Test
  fun `no system messages means empty systemText`() {
    val messages = listOf(
      ChatMessage(role = "user", content = JsonPrimitive("Hello")),
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("", result.systemText)
    assertEquals("Hello", result.promptText)
  }

  @Test
  fun `assistant with tool_calls rendered as bracket text`() {
    val toolCallsJson = buildJsonArray {
      add(buildJsonObject {
        put("id", "call_abc")
        put("type", "function")
        put("function", buildJsonObject {
          put("name", "get_weather")
          put("arguments", "{\"city\": \"Perth\"}")
        })
      })
    }
    val messages = listOf(
      ChatMessage(role = "assistant", content = JsonPrimitive(""), toolCalls = toolCallsJson),
    )
    val result = PromptFlattener.flatten(messages)
    assertTrue(result.promptText.contains("[assistant called tool get_weather"), "prompt: ${result.promptText}")
    assertTrue(result.promptText.contains("Perth"), "prompt: ${result.promptText}")
  }

  @Test
  fun `tool result message rendered as bracket format`() {
    val messages = listOf(
      ChatMessage(role = "tool", content = JsonPrimitive("42"), toolCallId = "call_xyz"),
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("[tool call_xyz result]: 42", result.promptText)
  }

  @Test
  fun `tool result with null toolCallId uses unknown placeholder`() {
    val messages = listOf(
      ChatMessage(role = "tool", content = JsonPrimitive("result"), toolCallId = null),
    )
    val result = PromptFlattener.flatten(messages)
    assertEquals("[tool unknown result]: result", result.promptText)
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
  fun `empty message list returns empty prompt and no images`() {
    val result = PromptFlattener.flatten(emptyList())
    assertEquals("", result.promptText)
    assertEquals(0, result.images.size)
  }

  @Test
  fun `JsonNull content throws IllegalArgumentException`() {
    val messages = listOf(ChatMessage(role = "user", content = JsonNull))
    assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
  }

  @Test
  fun `data URL without comma throws IllegalArgumentException`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          put("url", "data:image/png;base64NO_COMMA_HERE")
        })
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
  }

  @Test
  fun `malformed base64 throws IllegalArgumentException with Malformed base64 message`() {
    val parts = buildJsonArray {
      add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
          // valid data URL structure but base64 payload is garbage
          put("url", "data:image/png;base64,!!!not-valid-base64!!!")
        })
      })
    }
    val messages = listOf(ChatMessage(role = "user", content = parts))
    val ex = assertFailsWith<IllegalArgumentException> {
      PromptFlattener.flatten(messages)
    }
    assertTrue(
      ex.message?.contains("Malformed base64") == true,
      "Expected 'Malformed base64' in exception message, got: ${ex.message}",
    )
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

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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolCallParserTest {

  // ── Clean fenced block ────────────────────────────────────────────────────

  @Test
  fun `clean fenced block is parsed`() {
    val text = """
      ```tool_call
      {"name": "get_weather", "arguments": {"city": "Perth"}}
      ```
    """.trimIndent()
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals(1, result.calls.size)
    assertEquals("get_weather", result.calls[0].name)
    // arguments must be a JSON string containing the compact JSON
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(result.calls[0].argumentsJson)
    assertNotNull(parsed.jsonObject["city"])
  }

  @Test
  fun `fenced block arguments round-trip via Json parse`() {
    val text = "```tool_call\n{\"name\": \"echo\", \"arguments\": {\"msg\": \"hello world\"}}\n```"
    val result = ToolCallParser.parse(text) as ToolCallParser.ParseResult.ToolCalls
    val argsObj = kotlinx.serialization.json.Json.parseToJsonElement(result.calls[0].argumentsJson).jsonObject
    assertEquals("hello world", argsObj["msg"]?.jsonPrimitive?.content)
  }

  // ── Block with leading prose ──────────────────────────────────────────────

  @Test
  fun `block with leading prose extracts prefix text`() {
    val text = "Sure, let me check the weather.\n```tool_call\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Sydney\"}}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals("get_weather", result.calls[0].name)
    assertTrue(result.prefixText.contains("Sure"), "prefix text should contain prose: ${result.prefixText}")
  }

  @Test
  fun `block with trailing newlines is still parsed`() {
    val text = "```tool_call\n{\"name\": \"fn\", \"arguments\": {}}\n```\n\n"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals("fn", result.calls[0].name)
  }

  // ── Bare JSON object alone ────────────────────────────────────────────────

  @Test
  fun `bare JSON object alone is detected`() {
    val text = """{"name": "lookup", "arguments": {"q": "kotlin"}}"""
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals("lookup", result.calls[0].name)
    assertEquals("", result.prefixText)
  }

  @Test
  fun `bare JSON object with extra whitespace is detected`() {
    val text = "  \n  {\"name\": \"fn\", \"arguments\": {\"x\": 1}}  \n  "
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals("fn", result.calls[0].name)
  }

  // ── Multiple blocks ───────────────────────────────────────────────────────

  @Test
  fun `multiple fenced blocks are all parsed`() {
    val text = """
      First call:
      ```tool_call
      {"name": "tool_a", "arguments": {"x": 1}}
      ```
      Second call:
      ```tool_call
      {"name": "tool_b", "arguments": {"y": 2}}
      ```
    """.trimIndent()
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals(2, result.calls.size)
    assertEquals("tool_a", result.calls[0].name)
    assertEquals("tool_b", result.calls[1].name)
  }

  // ── Malformed JSON in block → NoToolCall ─────────────────────────────────

  @Test
  fun `malformed JSON in fenced block returns NoToolCall`() {
    val text = "```tool_call\n{not valid json}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
    // Original text must be returned unchanged
    assertEquals(text, result.text)
  }

  @Test
  fun `fenced block with missing name key returns NoToolCall`() {
    val text = "```tool_call\n{\"arguments\": {}}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
  }

  @Test
  fun `fenced block with missing arguments key returns NoToolCall`() {
    val text = "```tool_call\n{\"name\": \"fn\"}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
  }

  // ── No block ──────────────────────────────────────────────────────────────

  @Test
  fun `plain text with no block returns NoToolCall`() {
    val text = "The weather in Perth is sunny and 22°C."
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
    assertEquals(text, result.text)
  }

  @Test
  fun `empty string returns NoToolCall`() {
    val result = ToolCallParser.parse("")
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
  }

  // ── Nested braces in arguments ────────────────────────────────────────────

  @Test
  fun `nested braces in arguments are handled`() {
    val text = "```tool_call\n{\"name\": \"create\", \"arguments\": {\"data\": {\"nested\": {\"deep\": true}}}}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    assertEquals("create", result.calls[0].name)
    val args = kotlinx.serialization.json.Json.parseToJsonElement(result.calls[0].argumentsJson).jsonObject
    assertNotNull(args["data"])
  }

  // ── Non-tool_call code fence must NOT trigger ─────────────────────────────

  @Test
  fun `python code fence is not mistaken for tool_call`() {
    val text = "Here is the code:\n```python\nprint('hello')\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
    assertEquals(text, result.text)
  }

  @Test
  fun `json code fence without tool_call label is not detected`() {
    val text = "```json\n{\"name\": \"fn\", \"arguments\": {}}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
  }

  @Test
  fun `unlabelled code fence is not detected`() {
    val text = "```\n{\"name\": \"fn\", \"arguments\": {}}\n```"
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.NoToolCall>(result)
  }

  // ── Arguments as string (passthrough) ────────────────────────────────────

  @Test
  fun `arguments already a JSON string in model output is passed through`() {
    // Some models may emit arguments as an already-serialized string
    val text = """{"name": "fn", "arguments": "{\"key\": \"value\"}"}"""
    val result = ToolCallParser.parse(text)
    assertIs<ToolCallParser.ParseResult.ToolCalls>(result)
    // The content of the string should be preserved as-is
    assertEquals("{\"key\": \"value\"}", result.calls[0].argumentsJson)
  }

  // ── Helper extension ──────────────────────────────────────────────────────

  private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject

  private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
}

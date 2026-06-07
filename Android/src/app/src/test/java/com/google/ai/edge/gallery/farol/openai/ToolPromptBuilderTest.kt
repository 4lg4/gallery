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

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolPromptBuilderTest {

  private fun makeTool(name: String, description: String? = null, hasParams: Boolean = false): ToolDef {
    val params = if (hasParams) buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("city") { put("type", "string") }
      }
    } else null
    return ToolDef(function = FunctionDef(name = name, description = description, parameters = params))
  }

  @Test
  fun `output contains tool name`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("get_weather")))
    assertTrue(result.contains("get_weather"), "Tool name not found: $result")
  }

  @Test
  fun `output contains description when provided`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn", description = "Get current weather")))
    assertTrue(result.contains("Get current weather"), "Description not found: $result")
  }

  @Test
  fun `output contains no_description placeholder when null`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn", description = null)))
    assertTrue(result.contains("(no description)"), "Placeholder not found: $result")
  }

  @Test
  fun `output contains input schema section`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn", hasParams = true)))
    assertTrue(result.contains("Input schema"), "Schema section not found: $result")
    assertTrue(result.contains("city"), "Schema content 'city' not found: $result")
  }

  @Test
  fun `output contains tool_call fence instruction`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn")))
    assertTrue(result.contains("```tool_call"), "Fence instruction not found: $result")
  }

  @Test
  fun `output contains nothing after instruction`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn")))
    assertTrue(result.contains("answer normally"), "Normal-answer instruction not found: $result")
  }

  @Test
  fun `multiple tools are all present`() {
    val tools = listOf(makeTool("tool_a", "First tool"), makeTool("tool_b", "Second tool"))
    val result = ToolPromptBuilder.build(tools)
    assertTrue(result.contains("tool_a"), "tool_a not found: $result")
    assertTrue(result.contains("tool_b"), "tool_b not found: $result")
    assertTrue(result.contains("First tool"), "Description for tool_a not found: $result")
    assertTrue(result.contains("Second tool"), "Description for tool_b not found: $result")
  }

  @Test
  fun `output mirrors MCP tool name format`() {
    // Should look like: MCP tool name: "get_weather"
    val result = ToolPromptBuilder.build(listOf(makeTool("get_weather")))
    assertTrue(result.contains("""MCP tool name: "get_weather""""), "MCP format not found: $result")
  }

  @Test
  fun `numbered list starts from 1`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn_a"), makeTool("fn_b")))
    assertTrue(result.contains("1."), "No number '1.' found: $result")
    assertTrue(result.contains("2."), "No number '2.' found: $result")
  }

  @Test
  fun `empty params shows empty schema`() {
    val result = ToolPromptBuilder.build(listOf(makeTool("fn", hasParams = false)))
    // Should still have some schema indicator (either {} or the section header)
    assertTrue(result.contains("{}"), "Empty schema not found: $result")
  }
}

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

/**
 * Builds a text block describing available tools that is prepended to the system instruction.
 *
 * The format mirrors [com.google.ai.edge.gallery.customtasks.agentchat.McpManagerViewModel
 * .getToolsPrompt] so the model sees a familiar shape:
 *
 * ```
 * MCP tool name: "<name>"
 * - Description: <description>
 * - Input schema: <json>
 * ```
 *
 * Additionally appends a strict instruction telling the model exactly how to signal a tool call.
 */
object ToolPromptBuilder {

  // Lenient Json for pretty-printing parameter schemas.
  private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

  /**
   * Returns a system-instruction text block describing [tools] and instructing the model to emit
   * a `\`\`\`tool_call` fenced block when it decides to call one.
   *
   * @param tools Non-empty list of [ToolDef]s to describe.
   * @return Multi-line string ready to be appended to (or used as) the system instruction.
   */
  fun build(tools: List<ToolDef>): String = buildString {
    appendLine("You have access to the following tools:")
    appendLine()

    tools.forEachIndexed { idx, tool ->
      val fn = tool.function
      val schemaText = if (fn.parameters != null) {
        try {
          prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), fn.parameters)
        } catch (_: Exception) {
          fn.parameters.toString()
        }
      } else {
        "{}"
      }
      appendLine("${idx + 1}. MCP tool name: \"${fn.name}\"")
      appendLine("- Description: ${fn.description ?: "(no description)"}")
      append("- Input schema: $schemaText")
      if (idx < tools.lastIndex) appendLine()
      appendLine()
    }

    appendLine("When you decide to call a tool, output ONLY a block exactly like:")
    appendLine("```tool_call")
    appendLine("""{"name": "<tool_name>", "arguments": {…}}""")
    appendLine("```")
    append("and nothing after it. If no tool is needed, answer normally.")
  }
}

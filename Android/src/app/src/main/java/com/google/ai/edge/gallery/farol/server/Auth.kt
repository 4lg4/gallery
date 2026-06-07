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

package com.google.ai.edge.gallery.farol.server

/**
 * Stateless authorization helper for the Farol HTTP server.
 *
 * Accepts credentials in two forms:
 * - `Authorization: Bearer <key>` header (standard OpenAI SDK style).
 * - `X-Farol-Key: <key>` header (lightweight alternative for non-OpenAI clients).
 *
 * A blank [configuredKey] always rejects — the server must be configured with a non-empty key
 * before it accepts any requests.
 */
object Auth {

  private const val BEARER_PREFIX = "Bearer "

  /**
   * Returns true when at least one of [authHeader] or [farolHeader] carries [configuredKey]
   * exactly (after stripping the `Bearer ` prefix and trimming whitespace from [authHeader]).
   *
   * @param authHeader Value of the `Authorization` header, or null if absent.
   * @param farolHeader Value of the `X-Farol-Key` header, or null if absent.
   * @param configuredKey The server's expected API key.  Blank → always false.
   */
  fun isAuthorized(
    authHeader: String?,
    farolHeader: String?,
    configuredKey: String,
  ): Boolean {
    if (configuredKey.isBlank()) return false

    // Check Authorization: Bearer <key>
    if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
      val token = authHeader.removePrefix(BEARER_PREFIX).trim()
      if (token == configuredKey) return true
    }

    // Check X-Farol-Key: <key>
    if (farolHeader != null && farolHeader == configuredKey) return true

    return false
  }
}

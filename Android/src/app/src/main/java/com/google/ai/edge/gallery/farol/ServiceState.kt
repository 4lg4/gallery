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

package com.google.ai.edge.gallery.farol

/**
 * Pure (framework-free) helpers for FAROL service startup decisions and notification text.
 *
 * Extracted from [FarolService] so they can be unit-tested without Android framework classes.
 */
object ServiceState {

  // ── Key-file validation ──────────────────────────────────────────────────

  /**
   * Returns a human-readable problem description if [content] is not a usable API key,
   * or `null` if the content is acceptable.
   *
   * - `null` content → file was missing (could not be read)
   * - blank string  → file exists but is empty or whitespace-only
   * - non-blank     → OK, returns `null`
   */
  fun keyFileProblem(content: String?): String? = when {
    content == null  -> "farol.key missing — run push-key.sh"
    content.isBlank() -> "farol.key is blank — run push-key.sh"
    else              -> null
  }

  // ── Notification text states ─────────────────────────────────────────────

  /** Sealed hierarchy for the three meaningful FarolService notification states. */
  sealed class NotificationState {
    /** Model is being loaded; engine init in progress. */
    object Loading : NotificationState()

    /** Server is running and accepting requests. */
    data class Serving(val port: Int, val modelName: String) : NotificationState()

    /** An unrecoverable error occurred; service is stopping. */
    data class Error(val message: String) : NotificationState()
  }

  /**
   * Maps a [NotificationState] to the display string shown in the persistent notification.
   */
  fun notificationText(state: NotificationState): String = when (state) {
    is NotificationState.Loading         -> "loading model…"
    is NotificationState.Serving         -> "serving :${state.port} — ${state.modelName}"
    is NotificationState.Error           -> "error: ${state.message}"
  }
}

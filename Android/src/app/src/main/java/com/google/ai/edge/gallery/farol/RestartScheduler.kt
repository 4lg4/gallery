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
 * Pure (framework-free) constants for FAROL service restart scheduling.
 *
 * Extracted so that the restart delay is a named, tested constant rather than a magic literal
 * scattered across the Android glue code.
 */
object RestartScheduler {

  /**
   * Delay in milliseconds before re-starting [FarolService] after a recents-swipe
   * (`onTaskRemoved`).
   *
   * 2 seconds is long enough for the system to finish tearing down the task but short enough to
   * feel instantaneous to a user waiting for LAN inference to return.
   */
  fun restartDelayMillis(): Long = 2_000L
}

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
 * Pure (framework-free) decision logic for the FAROL periodic self-heal watchdog.
 *
 * The watchdog [FarolWatchdogWorker] periodically checks whether [FarolService] is alive and, if
 * not, attempts to restart it.  The restart *decision* lives here so it can be unit-tested without
 * the Android runtime or WorkManager.
 */
object FarolWatchdog {

  /**
   * Returns `true` if the watchdog should attempt to restart [FarolService].
   *
   * The decision is trivially `!serviceRunning`; the function exists as a named, tested boundary
   * between the pure policy and the Android glue in [FarolWatchdogWorker].
   *
   * @param serviceRunning the current value of [FarolService.isRunning]
   */
  fun shouldRestart(serviceRunning: Boolean): Boolean = !serviceRunning
}

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
 * Pure (framework-free) policy for deciding when FAROL should auto-start.
 *
 * Extracted from [BootReceiver] so the decision logic can be unit-tested without the Android
 * runtime.  The two action constants are spelled out as string literals (rather than referencing
 * [android.content.Intent.ACTION_BOOT_COMPLETED] etc.) so this object compiles and runs on a
 * plain JVM — the literal values are defined by the Android platform spec and will never change.
 */
object AutoStartPolicy {

  /**
   * Returns `true` if FAROL should start (or restart) its foreground service in response to the
   * given broadcast [action].
   *
   * Handled actions:
   * - `android.intent.action.BOOT_COMPLETED` — device finished booting.
   * - `android.intent.action.MY_PACKAGE_REPLACED` — this APK was just updated; only delivered to
   *   the replaced app, so no `<data>` filter is needed in the manifest.
   *
   * Any other action (including `null`) returns `false`.
   */
  fun shouldStartService(action: String?): Boolean = action == ACTION_BOOT_COMPLETED ||
    action == ACTION_MY_PACKAGE_REPLACED

  // Literal values from the Android platform — stable, never change.
  private const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
  private const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
}

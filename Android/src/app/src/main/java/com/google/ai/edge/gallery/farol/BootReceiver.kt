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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "BootReceiver"

/**
 * FAROL — Starts [FarolService] after device boot **or** after an APK reinstall.
 *
 * ## Handled broadcasts
 * - `android.intent.action.BOOT_COMPLETED` — device finished booting.
 * - `android.intent.action.MY_PACKAGE_REPLACED` — this APK was just updated.  Android delivers
 *   this action *only* to the replaced app, so no `<data>` filter is needed in the manifest.
 *
 * The decision of which actions trigger a start is delegated to [AutoStartPolicy.shouldStartService]
 * so it can be tested independently of the Android runtime.
 *
 * ## Battery-optimisation exemption required for API 35+
 * Android 15 (API 35) restricts foreground-service starts from
 * [Intent.ACTION_BOOT_COMPLETED] receivers unless the app is on the
 * battery-optimisation allowlist.  The FAROL appliance setup script (Task 6)
 * grants this via:
 * ```
 * adb shell dumpsys deviceidle whitelist +com.google.aiedge.gallery
 * ```
 * Without the exemption, [startForegroundService] throws
 * [android.app.ForegroundServiceStartNotAllowedException] (a subclass of
 * [IllegalStateException]) on API 35+.  The call is wrapped in a try/catch so
 * a missing exemption produces a clear log instead of a silent crash.
 */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (AutoStartPolicy.shouldStartService(intent.action)) {
      Log.i(TAG, "onReceive: action=${intent.action} — starting FarolService")
      try {
        context.startForegroundService(Intent(context, FarolService::class.java))
        // Also (re-)arm the periodic watchdog here.  Belt-and-suspenders: FarolService.onStartCommand
        // calls schedule() too, but on some devices the foreground-service start is async and the
        // watchdog window between boot and onStartCommand is a real gap.  The call is idempotent
        // (ExistingPeriodicWorkPolicy.KEEP), so the double-schedule is harmless.
        FarolWatchdogWorker.schedule(context)
      } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 35+) is thrown when the app is
        // not on the battery-optimisation allowlist.  Grant the exemption with:
        //   adb shell dumpsys deviceidle whitelist +com.google.aiedge.gallery
        Log.e(
          TAG,
          "Cannot start FarolService from ${intent.action} — grant battery-optimisation " +
            "exemption: adb shell dumpsys deviceidle whitelist +com.google.aiedge.gallery",
          e,
        )
      }
    }
  }
}

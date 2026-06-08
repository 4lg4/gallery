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

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val TAG = "FarolWatchdogWorker"

/**
 * FAROL — periodic self-heal watchdog.
 *
 * Runs every 15 minutes (WorkManager minimum interval).  Reads the in-process
 * [FarolService.isRunning] flag and, if [FarolWatchdog.shouldRestart] indicates the service has
 * stopped, issues a new `startForegroundService` call to bring it back.
 *
 * When the service process itself was killed by the OS, this Worker executes in a fresh process
 * where [FarolService.isRunning] is initialised to `false`, so [FarolWatchdog.shouldRestart]
 * returns `true` and the restart fires correctly — no persistence is needed.
 *
 * This is a *defence-in-depth* measure against organic kills (low memory, doze, etc.).  It does
 * **not** help after a FORCE STOP — Android prevents any automatic restart from the
 * package-stopped state until the user manually launches the app.
 *
 * The pure restart *decision* lives in [FarolWatchdog] and is unit-tested independently.
 */
class FarolWatchdogWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val running = FarolService.isRunning
    Log.d(TAG, "doWork: FarolService.isRunning=$running")

    if (FarolWatchdog.shouldRestart(running)) {
      Log.i(TAG, "doWork: service not running — attempting restart via startForegroundService")
      try {
        applicationContext.startForegroundService(
          Intent(applicationContext, FarolService::class.java)
        )
        Log.i(TAG, "doWork: startForegroundService dispatched")
      } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) — app is in the background and
        // not on the battery-optimisation allowlist, or the system throttled the start.
        // Log and return success so WorkManager does not retry aggressively.
        Log.e(
          TAG,
          "doWork: cannot start FarolService (ForegroundServiceStartNotAllowedException) — " +
            "ensure adb shell dumpsys deviceidle whitelist +com.google.aiedge.gallery",
          e,
        )
      }
    } else {
      Log.d(TAG, "doWork: service is running — no action needed")
    }

    return Result.success()
  }

  companion object {

    private const val WORK_NAME = "farol-watchdog"

    /**
     * Enqueues (or re-arms, idempotently via [ExistingPeriodicWorkPolicy.KEEP]) the watchdog
     * [PeriodicWorkRequest].
     *
     * Called from:
     * - [BootReceiver.onReceive] — after device boot or APK reinstall.
     * - [FarolService.onStartCommand] — after the service starts, so the watchdog is always
     *   armed even if BootReceiver was not the entry point.
     *
     * @param ctx any [Context]; [WorkManager.getInstance] resolves the application context
     *   internally.
     */
    fun schedule(ctx: Context) {
      val request = PeriodicWorkRequestBuilder<FarolWatchdogWorker>(15, TimeUnit.MINUTES).build()
      WorkManager.getInstance(ctx)
        .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
      Log.d(TAG, "schedule: watchdog periodic work enqueued (KEEP) — interval 15 min")
    }
  }
}

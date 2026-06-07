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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.google.ai.edge.gallery.farol.engine.LiteRtLmEngine
import com.google.ai.edge.gallery.farol.engine.ModelLocator
import com.google.ai.edge.gallery.farol.server.startFarolServer
import io.ktor.server.engine.EmbeddedServer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "FarolService"
private const val PORT = 8080

/**
 * FAROL — LAN LLM inference server foreground service.
 *
 * ## Lifecycle
 * 1. [onCreate] — creates the notification channel, idempotently.
 * 2. [onStartCommand] — calls [startForeground] immediately (crash-guard), then launches the
 *    startup sequence on a background coroutine if not already running.
 * 3. Background startup:
 *    a. Reads `filesDir/farol.key`; stops with an error notification if missing/blank.
 *    b. Calls [ModelLocator.requireModel]; stops with an error notification if absent.
 *    c. Constructs [LiteRtLmEngine] (slow — 10–30 s on first cold start).
 *    d. Starts the Ktor CIO server via [startFarolServer].
 *    e. Updates the persistent notification to "serving :8080 — <modelName>".
 * 4. [onDestroy] — cancels the coroutine scope; tears down server + engine on a plain
 *    background [Thread] to avoid blocking the main thread (see note below).
 *
 * ## onDestroy teardown — why a plain Thread, not runBlocking or a coroutine scope
 * [LiteRtLmEngine.close] calls `runBlocking { mutex.lock() }` internally, blocking until any
 * in-flight inference finishes.  Calling it from the main thread would ANR immediately.
 * Posting it to a new [Thread] keeps the main thread free.  The scope is already cancelled at
 * that point, so we cannot use a scope-launched coroutine.  A daemon thread is acceptable here
 * because the OS will reclaim native resources when the process exits; the Thread merely gives
 * in-flight requests a best-effort grace window (server: 5 s, engine: unbounded).
 *
 * ## Idempotency
 * [onStartCommand] checks [started] before launching the startup coroutine.  Double-starts from
 * [BootReceiver] + [MainActivity] are safe and simply return [START_STICKY].
 */
class FarolService : Service() {

  // ── Service scope (cancelled in onDestroy) ───────────────────────────────

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // ── Runtime state (nullable = not yet started) ───────────────────────────

  @Volatile private var started = false
  @Volatile private var engine: LiteRtLmEngine? = null
  @Volatile private var server: EmbeddedServer<*, *>? = null
  @Volatile private var wakeLock: PowerManager.WakeLock? = null

  // ── Lifecycle ────────────────────────────────────────────────────────────

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "FAROL server", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Immediate startForeground crash guard — must be called before any async work.
    startForeground(NOTIFICATION_ID, buildNotification("starting…"))

    if (started) {
      Log.d(TAG, "onStartCommand: already running — ignoring duplicate start")
      return START_STICKY
    }
    started = true

    // Acquire wake lock so the CPU stays on while inference runs over WiFi.
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "farol:server").also {
      it.acquire()
    }

    scope.launch {
      try {
        // ── a. Read API key ───────────────────────────────────────────────
        val keyFile = File(filesDir, "farol.key")
        val keyContent = if (keyFile.exists()) keyFile.readText().trim() else null
        val keyProblem = ServiceState.keyFileProblem(keyContent)
        if (keyProblem != null) {
          Log.e(TAG, "Key problem: $keyProblem")
          updateNotification(ServiceState.NotificationState.Error(keyProblem))
          stopSelf()
          return@launch
        }
        val apiKey = keyContent!! // non-null, non-blank after keyFileProblem == null

        // ── b. Locate model ───────────────────────────────────────────────
        val modelFile = try {
          ModelLocator.requireModel(this@FarolService)
        } catch (e: IllegalStateException) {
          val msg = e.message ?: "model not found"
          Log.e(TAG, "Model missing: $msg")
          updateNotification(ServiceState.NotificationState.Error(msg))
          stopSelf()
          return@launch
        }

        // ── c. Load engine (slow) ─────────────────────────────────────────
        updateNotification(ServiceState.NotificationState.Loading)
        Log.d(TAG, "Initialising engine from ${modelFile.absolutePath}")
        val liteRtEngine = LiteRtLmEngine(
          modelPath = modelFile.absolutePath,
          modelDisplayName = ModelLocator.MODEL_NAME,
        )
        engine = liteRtEngine
        Log.d(TAG, "Engine ready")

        // ── d. Start Ktor server ──────────────────────────────────────────
        val embeddedServer = startFarolServer(liteRtEngine, apiKey, PORT)
        server = embeddedServer
        Log.d(TAG, "Server started on :$PORT")

        // ── e. Update notification ────────────────────────────────────────
        updateNotification(
          ServiceState.NotificationState.Serving(PORT, liteRtEngine.modelName)
        )
      } catch (e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        Log.e(TAG, "Startup failed: $msg", e)
        updateNotification(ServiceState.NotificationState.Error(msg))
        stopSelf()
      }
    }

    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.d(TAG, "onDestroy — cancelling scope")
    scope.cancel()

    // Capture refs before nulling so the thread closure sees them.
    val capturedServer = server
    val capturedEngine = engine
    val capturedWakeLock = wakeLock
    server = null
    engine = null
    wakeLock = null

    // Tear down on a plain Thread:
    //   - LiteRtLmEngine.close() calls runBlocking{mutex.lock()} internally — blocks until any
    //     in-flight inference finishes.  Must NOT run on the main thread (ANR).
    //   - The service scope is already cancelled, so we cannot use a scope-coroutine here.
    //   - A daemon thread is acceptable: the OS reclaims native resources on process exit; this
    //     thread only gives in-flight requests a best-effort grace window.
    Thread(
      {
        runCatching {
          Log.d(TAG, "teardown: stopping server…")
          capturedServer?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
          Log.d(TAG, "teardown: server stopped")
        }.onFailure { Log.w(TAG, "teardown: server stop failed", it) }

        runCatching {
          Log.d(TAG, "teardown: closing engine…")
          capturedEngine?.close()
          Log.d(TAG, "teardown: engine closed")
        }.onFailure { Log.w(TAG, "teardown: engine close failed", it) }

        runCatching {
          if (capturedWakeLock?.isHeld == true) capturedWakeLock.release()
          Log.d(TAG, "teardown: wake lock released")
        }.onFailure { Log.w(TAG, "teardown: wake lock release failed", it) }
      },
      "farol-teardown",
    ).also { it.isDaemon = true }.start()
  }

  // ── Notification helpers ─────────────────────────────────────────────────

  private fun buildNotification(text: String): Notification =
    Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("FAROL server")
      .setContentText(text)
      .setSmallIcon(android.R.drawable.ic_menu_share)
      .build()

  private fun updateNotification(state: ServiceState.NotificationState) {
    val text = ServiceState.notificationText(state)
    val notification = buildNotification(text)
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(NOTIFICATION_ID, notification)
  }

  companion object {
    private const val CHANNEL_ID = "farol_server"
    private const val NOTIFICATION_ID = 1001
  }
}

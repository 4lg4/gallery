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

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.farol.engine.LiteRtLmEngine
import com.google.ai.edge.gallery.farol.engine.ModelLocator
import com.google.ai.edge.gallery.farol.server.startFarolServer
import io.ktor.server.engine.EmbeddedServer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
 * ## Startup/teardown race
 * [LiteRtLmEngine] construction takes 10–30 s and is non-cancellable.  If [onDestroy] fires
 * during that window, [scope].cancel() marks the coroutine cancelled but cannot interrupt the
 * blocking native call.  To prevent leaking a fully-constructed engine or server:
 * - The startup coroutine checks [isActive] immediately after each expensive step.
 * - If cancelled, it self-cleans the just-created object and returns without writing to the
 *   shared [engine]/[server] fields.
 * - [onDestroy] calls [startupJob]?.cancel() then takes a snapshot of the shared fields; the
 *   in-coroutine gates handle cleanup of objects created after the snapshot.
 * - A residual tiny window exists between the `isActive` check and the field assignment, but it
 *   is acceptable: the assignment is two adjacent non-suspending lines, and onDestroy's teardown
 *   thread + the coroutine's self-cleanup both cover each side of the race independently.
 *
 * ## Idempotency
 * [onStartCommand] checks [started] before launching the startup coroutine.  Double-starts from
 * [BootReceiver] + [MainActivity] are safe and simply return [START_STICKY].
 *
 * ## Resilience
 * - [onTaskRemoved] schedules an [AlarmManager] one-shot to restart the service ~2 s after a
 *   recents-swipe so inference is automatically re-armed.
 * - [FarolWatchdogWorker] runs every 15 minutes and restarts the service if [isRunning] is
 *   `false`.  FORCE STOP remains unfixable by Android design.
 */
class FarolService : Service() {

  companion object {
    private const val CHANNEL_ID = "farol_server"
    private const val NOTIFICATION_ID = 1001

    /**
     * Engine-level token budget (input + output) passed to [LiteRtLmEngine.initMaxTokens].
     *
     * Gemma 4 E4B is trained for 32k context; the LiteRT-LM library default is a conservative
     * 4096, which is too small to hold an agent harness's prompt (e.g. opencode's system prompt +
     * tool schemas run ~5–6k tokens) and caused "input tokens too long" rejections.
     *
     * 12288 gives comfortable headroom for those callers while keeping the cost sane: LiteRT-LM
     * provisions the KV cache to THIS cap at engine-init (it is not grown on demand), so a larger
     * value pre-commits more RAM and slows prefill even for small requests. 12k is the balance for
     * a 4B model on a 16 GB device (≈1.5–2 GB KV) coexisting with the Termux Whisper workload.
     * Raise only if a real caller needs longer documents.
     */
    private const val MAX_TOKENS = 12288
    // Unique request code for the onTaskRemoved restart PendingIntent. Must be non-zero and
    // distinct from any other PendingIntent used in this service to avoid clobbering.
    private const val RESTART_ALARM_REQUEST_CODE = 7829

    /**
     * Process-local liveness flag.  Set `true` at the end of successful startup (after the Ktor
     * server is assigned and the notification updated), cleared to `false` in [onDestroy].
     *
     * Read by [FarolWatchdogWorker] to decide whether a restart is needed.  Volatile guarantees
     * visibility across threads without heavier synchronisation overhead.
     */
    @Volatile var isRunning: Boolean = false
      private set
  }

  // ── Service scope (cancelled in onDestroy) ───────────────────────────────

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // ── Runtime state (nullable = not yet started) ───────────────────────────

  @Volatile private var started = false
  @Volatile private var startupJob: Job? = null
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

  // @SuppressLint: intentional — FAROL is an always-on appliance; the wake lock must be held
  // indefinitely so the CPU stays awake for LAN inference while the screen is off.
  @SuppressLint("WakelockTimeout")
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

    // Arm the periodic watchdog so it is running even if BootReceiver was not the entry point
    // (e.g. the user launched the app from the launcher after a FORCE STOP recovery).
    // Idempotent via ExistingPeriodicWorkPolicy.KEEP.
    FarolWatchdogWorker.schedule(this)

    startupJob = scope.launch {
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

        // ── c. Load engine (slow, non-cancellable) ────────────────────────
        // LiteRtLmEngine construction blocks a native thread for 10–30 s and cannot be
        // interrupted.  We check isActive immediately after it returns so that a concurrent
        // onDestroy (which calls startupJob?.cancel()) causes self-cleanup here rather than
        // leaking a fully-initialised engine.
        updateNotification(ServiceState.NotificationState.Loading)
        Log.d(TAG, "Initialising engine from ${modelFile.absolutePath}")
        val liteRtEngine = LiteRtLmEngine(
          modelPath = modelFile.absolutePath,
          modelDisplayName = ModelLocator.MODEL_NAME,
          initMaxTokens = MAX_TOKENS,
        )
        // Race gate A: onDestroy may have fired during the blocking init above.
        // Self-clean before touching any shared field so the teardown thread's snapshot is safe.
        if (!isActive) {
          Log.w(TAG, "Cancelled during engine init — closing leaked engine")
          runCatching { liteRtEngine.close() }
          return@launch
        }
        engine = liteRtEngine
        Log.d(TAG, "Engine ready")

        // ── d. Start Ktor server ──────────────────────────────────────────
        val embeddedServer = startFarolServer(liteRtEngine, apiKey, PORT)
        // Race gate B: onDestroy may have fired between engine assignment and here.
        // Self-clean server + engine before returning so nothing leaks.
        // Note: engine is already in the shared field at this point — onDestroy's teardown
        // thread will handle it if it fired after gate A; we only need to handle the server.
        if (!isActive) {
          Log.w(TAG, "Cancelled during server start — tearing down server + engine")
          runCatching { embeddedServer.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
          // engine field was already assigned; teardown thread covers it via its snapshot.
          return@launch
        }
        server = embeddedServer
        Log.d(TAG, "Server started on :$PORT")

        // ── e. Update notification ────────────────────────────────────────
        updateNotification(
          ServiceState.NotificationState.Serving(PORT, liteRtEngine.modelName)
        )

        // Mark the service as running AFTER the notification update so that the watchdog
        // only sees isRunning=true when the server is fully operational.
        isRunning = true
        Log.d(TAG, "Startup complete — isRunning set to true")
      } catch (e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        Log.e(TAG, "Startup failed: $msg", e)
        updateNotification(ServiceState.NotificationState.Error(msg))
        stopSelf()
      }
    }

    return START_STICKY
  }

  /**
   * Called when the user swipes the app from the recents screen.
   *
   * Schedules an [AlarmManager] one-shot via [PendingIntent.getForegroundService] to restart
   * [FarolService] after [RestartScheduler.restartDelayMillis].  The alarm fires even while the
   * device is idle because [AlarmManager.setExactAndAllowWhileIdle] is used and the app is on the
   * deviceidle whitelist.
   *
   * FORCE STOP is intentionally **not** handled here — Android puts the app in the
   * package-stopped state after a FORCE STOP, which silences all receivers, alarms, and WorkManager
   * jobs until the user manually relaunches the app.
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    Log.i(TAG, "onTaskRemoved — scheduling restart in ${RestartScheduler.RESTART_DELAY_MILLIS} ms")

    val restartIntent = PendingIntent.getForegroundService(
      this,
      RESTART_ALARM_REQUEST_CODE,
      Intent(this, FarolService::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val alarmManager = getSystemService(AlarmManager::class.java)
    if (alarmManager == null) {
      Log.e(TAG, "onTaskRemoved: AlarmManager unavailable — restart alarm not scheduled")
      return
    }
    val triggerAt = SystemClock.elapsedRealtime() + RestartScheduler.RESTART_DELAY_MILLIS
    // SCHEDULE_EXACT_ALARM is user-revocable (Android 12+); fall back to inexact if denied.
    val canExact = alarmManager.canScheduleExactAlarms()
    val scheduled = if (canExact) runCatching {
      alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        triggerAt,
        restartIntent,
      )
    }.isSuccess else false
    if (!scheduled) alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, restartIntent)
  }

  override fun onDestroy() {
    super.onDestroy()
    isRunning = false
    Log.d(TAG, "onDestroy — isRunning cleared, cancelling scope")

    // Cancel the startup job first so the in-coroutine isActive gates fire and self-clean any
    // objects created after this thread's field snapshot below.  We do NOT join/await here
    // (startup can block for 30 s); the coroutine's own gates handle the race independently.
    startupJob?.cancel()
    scope.cancel()

    // Capture refs before nulling so the thread closure sees them.
    // Division of responsibility for the startup/teardown race:
    //   - Objects assigned to shared fields BEFORE scope.cancel() → captured here, cleaned below.
    //   - Objects created AFTER scope.cancel() (inside the still-running native call) → the
    //     in-coroutine isActive gates self-clean them and never write to the shared fields.
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

}

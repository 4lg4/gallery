# FAROL FGS Resilience — Implementation Plan (2026-06-08)

## Context / evidence

`dumpsys activity exit-info com.google.aiedge.gallery` shows every process death in the
last 24h was self-inflicted: `PACKAGE UPDATED` ×2 (APK reinstall), `USER REQUESTED/FORCE STOP`
×2, `USER REQUESTED/REMOVE TASK` ×2 (recents swipe). **Zero low-memory or doze kills.** The
existing hardening (START_STICKY + foreground `specialUse` service + indefinite wakelock +
`BootReceiver` on `BOOT_COMPLETED` + deviceidle whitelist) is working.

Therefore this is NOT a "service gets reaped under load" problem. It is: nothing brings the
server back after **reinstall** or **recents-swipe**, and (defence-in-depth) there is no
periodic self-heal if an organic kill ever does happen.

Hard truth encoded in the plan: a **FORCE STOP** puts the app in the package-stopped state and
NOTHING (START_STICKY, AlarmManager, WorkManager, boot receiver) can restart it until a manual
launch. We do not attempt to defeat that — we only document it.

## Architecture constraint (testability)

The module has **no Robolectric and no mockk** — only JUnit4 + `kotlin.test` + ktor test host.
The existing code already extracts pure logic into `ServiceState` (unit-tested) and keeps the
Android shell (Service/Receiver) thin. **Follow that pattern exactly:** all new decision logic
goes into pure, JVM-testable functions; the Android glue is a thin delegator verified on-device
by smoke test, not unit-tested.

Module root: `Android/src/app/`
- Service: `src/main/java/com/google/ai/edge/gallery/farol/FarolService.kt`
- Receiver: `src/main/java/com/google/ai/edge/gallery/farol/BootReceiver.kt`
- Manifest: `src/main/AndroidManifest.xml`
- Pure logic + tests live under `.../farol/` and `src/test/java/.../farol/`
- WorkManager already a dependency (`androidx.work.runtime` 2.10.0) — used by DownloadWorker.

## Task 1 — Auto-restart after APK reinstall (`MY_PACKAGE_REPLACED`) [HIGH, observed ×2]

**Pure logic (TDD):** new `AutoStartPolicy` object with
`fun shouldStartService(action: String?): Boolean` — returns true for
`Intent.ACTION_BOOT_COMPLETED` and `Intent.ACTION_MY_PACKAGE_REPLACED`, false otherwise
(incl. null). Co-located test `AutoStartPolicyTest.kt` covering: boot → true,
my-package-replaced → true, null → false, random action → false.

**Glue:** rename `BootReceiver`'s intent handling to delegate to `AutoStartPolicy.shouldStartService(intent.action)`
instead of the inline `== ACTION_BOOT_COMPLETED` check. Keep the class name `BootReceiver`
(referenced in manifest) but update its KDoc. Add a second `<intent-filter>` for
`android.intent.action.MY_PACKAGE_REPLACED` to the receiver in `AndroidManifest.xml`.
`MY_PACKAGE_REPLACED` is delivered only to the replaced app and needs no `<data>` filter.

## Task 2 — Re-arm after recents-swipe (`onTaskRemoved` + AlarmManager) [MED, observed ×2]

**Pure logic (TDD):** `RestartScheduler` pure helper
`fun restartDelayMillis(): Long` returning the constant delay (e.g. 2000L) — trivial but keeps
the magic number tested/owned; OR fold into `AutoStartPolicy`. Keep it minimal; the value is
the testable unit.

**Glue:** override `FarolService.onTaskRemoved(rootIntent)` to schedule an `AlarmManager`
`setExactAndAllowWhileIdle` (or `set`) PendingIntent (`getForegroundService`) that re-starts
`FarolService` after `restartDelayMillis()`. Verified on-device.

## Task 3 — Periodic self-heal watchdog (WorkManager) [MED, defence-in-depth]

**Pure logic (TDD):** `FarolWatchdog` object
`fun shouldRestart(serviceRunning: Boolean): Boolean = !serviceRunning`. Co-located test.
(Keep the decision pure; the Worker just gathers `serviceRunning` and acts.)

**Glue:**
- `FarolWatchdogWorker : CoroutineWorker` — in `doWork()`: determine whether `FarolService`
  is running (check via `ActivityManager.getRunningServices` deprecated-but-works for own
  process, OR a process-local `@Volatile FarolService.isRunning` flag set in
  onStartCommand/onDestroy — prefer the flag, simpler + reliable for own app). If
  `FarolWatchdog.shouldRestart(running)` → `startForegroundService(FarolService)`. Return
  `Result.success()`.
- Schedule a `PeriodicWorkRequest` (15 min, the WorkManager minimum) via
  `enqueueUniquePeriodicWork("farol-watchdog", KEEP, ...)`. Trigger the scheduling from
  `BootReceiver` (after boot) and from `FarolService.onStartCommand` (after start) so it is
  always armed. Idempotent via KEEP.
- Add `FarolService.isRunning` `@Volatile` companion flag, set true at end of successful
  startup, false in onDestroy. (This flag is itself trivially unit-coverable if extracted, but
  is fine as a plain volatile; the watchdog DECISION is the tested unit.)

## Out of scope (documented, not built)

- Defeating FORCE STOP — impossible by Android design.
- `android:process` isolation — no crash-isolation need; single process is correct.
- Per-request behaviour, networking changes.

## Verification (hard gate)

1. `./gradlew :app:testDebugUnitTest` (JDK 21) — all existing 222 + new tests green.
2. Build debug APK, `adb install -r`, confirm via `dumpsys activity services` the service
   auto-restarts after reinstall (Task 1 live proof — the exact observed failure).
3. `scripts/farol/smoke.sh` against the device → all checks pass (no regression).
4. Swipe from recents → service returns within ~2s (Task 2 live proof).

JDK 21: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

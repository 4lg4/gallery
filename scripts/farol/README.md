# FAROL Deploy & Smoke Scripts

## Order of operations

1. **Build** — open project in Android Studio, build `app` (debug variant).
2. **Install** — `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. **Push key** — `./scripts/farol/push-key.sh` (generates `.farol/farol.key` if absent, installs into app `filesDir`).
4. **Launch once** — open Gallery app on device; FAROL HTTP server starts on `:8080`.
5. **Smoke** — `PIXEL_HOST=<device-ip> ./scripts/farol/smoke.sh`

## Boot autostart note

`push-key.sh` runs `adb shell dumpsys deviceidle whitelist +com.google.aiedge.gallery`,
exempting the app from Doze so the server survives reboots and background kills.

## Key management

`.farol/farol.key` is gitignored. Back it up separately or re-run `push-key.sh` to rotate.

## Design doc

`AGLA-CLAUDE/projects/pixel-edge-inference-appliance.md` (and the FAROL plan at `docs/superpowers/plans/2026-06-05-farol-pixel-llm-server.md`).

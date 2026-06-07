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

## API notes

- `max_tokens`: accepted and forwarded to the engine, but capped at engine-init time
  (`EngineConfig.maxNumTokens`, default 4096). There is no per-request token cap at the HTTP layer.
  Responses include the header `X-Farol-MaxTokens: engine-cap` when `max_tokens` was provided.
- `GET /metrics` (auth required): returns uptime, request/error counts per endpoint, last-request
  duration, and the loaded model name.
- `system` messages: extracted from the conversation and passed as `ConversationConfig.systemInstruction`
  (not inlined into the prompt text). When `tools` are provided, the tool-call prompt is appended to
  the same `systemInstruction`, separated by a blank line.

## Design doc

`AGLA-CLAUDE/projects/pixel-edge-inference-appliance.md` (and the FAROL plan at `docs/superpowers/plans/2026-06-05-farol-pixel-llm-server.md`).

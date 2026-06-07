#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KEY_DIR="$REPO_ROOT/.farol"
KEY_FILE="$KEY_DIR/farol.key"
APP_ID="com.google.aiedge.gallery"

# Generate key if absent
if [[ ! -f "$KEY_FILE" ]]; then
  mkdir -p "$KEY_DIR"
  openssl rand -hex 24 > "$KEY_FILE"
  echo "[push-key] Generated new API key at $KEY_FILE"
else
  echo "[push-key] Using existing key at $KEY_FILE"
fi

echo "[push-key] Pushing key to device..."
adb push "$KEY_FILE" /data/local/tmp/farol.key
# NOTE: no `sh -c` here — adb shell strips one quoting layer, which truncates the cp args.
adb shell run-as "$APP_ID" cp /data/local/tmp/farol.key files/farol.key
adb shell rm /data/local/tmp/farol.key
echo "[push-key] Key installed at app filesDir/farol.key"

echo "[push-key] Applying battery/doze exemption for boot autostart..."
adb shell dumpsys deviceidle whitelist +"$APP_ID"
echo "[push-key] $APP_ID added to deviceidle whitelist (idempotent)"

echo ""
echo "Next steps:"
echo "  1. Launch the Gallery app on the device once to initialize FAROL server."
echo "  2. Verify the server is up: PIXEL_HOST=<device-ip> ./scripts/farol/smoke.sh"
echo "  3. On subsequent boots the app (and server) will autostart via exemption."

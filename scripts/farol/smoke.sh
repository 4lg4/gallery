#!/usr/bin/env bash
set -euo pipefail

# Usage: PIXEL_HOST=10.66.66.196 [PORT=8080] ./scripts/farol/smoke.sh
PIXEL_HOST="${PIXEL_HOST:?PIXEL_HOST env var required (e.g. 10.66.66.196)}"
PORT="${PORT:-8080}"
BASE_URL="http://${PIXEL_HOST}:${PORT}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KEY_FILE="$REPO_ROOT/.farol/farol.key"

if [[ ! -f "$KEY_FILE" ]]; then
  echo "[smoke] ERROR: key file not found at $KEY_FILE — run push-key.sh first" >&2
  exit 1
fi

API_KEY="$(cat "$KEY_FILE")"
WRONG_KEY="wrong-key-000000000000000000000000"

pass() { echo "[PASS] $1"; }
fail() { echo "[FAIL] $1" >&2; exit 1; }

# ── a. GET /health (no auth) ──────────────────────────────────────────────────
echo "== health =="
HEALTH=$(curl -sf --max-time 10 "${BASE_URL}/health")
echo "$HEALTH" | grep -qi "ok" && pass "health contains 'ok'" || fail "health: expected 'ok', got: $HEALTH"

# ── b. GET /v1/models with valid Bearer ──────────────────────────────────────
echo "== models (valid key) =="
MODELS=$(curl -sf --max-time 10 -H "Authorization: Bearer ${API_KEY}" "${BASE_URL}/v1/models")
echo "$MODELS" | grep -q '"id"' && pass "models contains model id" || fail "models: unexpected response: $MODELS"

# ── c. GET /v1/models with WRONG key → 401 ───────────────────────────────────
echo "== models (wrong key → 401) =="
STATUS=$(curl -s --max-time 10 -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${WRONG_KEY}" "${BASE_URL}/v1/models")
[[ "$STATUS" == "401" ]] && pass "wrong key returns 401" || fail "expected 401, got: $STATUS"

# ── d. POST /v1/chat/completions (batch) ─────────────────────────────────────
echo "== chat/completions (batch) =="
T_START=$SECONDS
BODY='{"model":"default","messages":[{"role":"user","content":"Reply with exactly: FAROL OK"}],"max_tokens":16}'
COMPLETION=$(curl -sf --max-time 180 \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "$BODY" \
  "${BASE_URL}/v1/chat/completions")
T_ELAPSED=$(( SECONDS - T_START ))
echo "$COMPLETION" | grep -qi "FAROL OK" || fail "completion: response does not contain 'FAROL OK': $COMPLETION"
echo "$COMPLETION" | grep -qE '"object"\s*:\s*"chat\.completion"' || fail "completion: missing object=chat.completion: $COMPLETION"
pass "batch completion (${T_ELAPSED}s)"

# ── e. POST /v1/chat/completions (streaming) ─────────────────────────────────
echo "== chat/completions (streaming) =="
T_START=$SECONDS
STREAM_BODY='{"model":"default","messages":[{"role":"user","content":"Reply with exactly: FAROL OK"}],"max_tokens":16,"stream":true}'
STREAM_OUT=$(curl -sN --no-buffer --max-time 180 \
  -H "Authorization: Bearer ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d "$STREAM_BODY" \
  "${BASE_URL}/v1/chat/completions")
T_ELAPSED=$(( SECONDS - T_START ))
echo "$STREAM_OUT" | grep -q "^data: " || fail "streaming: no 'data: ' prefix lines found"
echo "$STREAM_OUT" | grep -q "chat.completion.chunk" || fail "streaming: missing chat.completion.chunk"
echo "$STREAM_OUT" | grep -qE '"finish_reason"\s*:\s*"stop"' || fail "streaming: missing finish_reason:stop"
echo "$STREAM_OUT" | grep -q "data: \[DONE\]" || fail "streaming: missing data: [DONE]"
pass "streaming (${T_ELAPSED}s)"

# ── f. X-Farol-Key header variant ────────────────────────────────────────────
echo "== models (X-Farol-Key header) =="
MODELS_ALT=$(curl -sf --max-time 10 -H "X-Farol-Key: ${API_KEY}" "${BASE_URL}/v1/models")
echo "$MODELS_ALT" | grep -q '"id"' && pass "X-Farol-Key header accepted" || fail "X-Farol-Key: unexpected response: $MODELS_ALT"

echo ""
echo "ALL SMOKE TESTS PASSED"

#!/usr/bin/env bash
# smoke_test.sh — validates smoke.sh against a local Python mock FAROL server.
# No device or real model required.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SMOKE="$REPO_ROOT/scripts/farol/smoke.sh"
MOCK_PORT=18099
MOCK_HOST=127.0.0.1
MOCK_PID=""
MOCK_PY="$REPO_ROOT/.farol/mock_server.py"
TEST_KEY_LINK="$REPO_ROOT/.farol/farol.key"

# ── Cleanup ───────────────────────────────────────────────────────────────────
cleanup() {
  [[ -n "$MOCK_PID" ]] && kill "$MOCK_PID" 2>/dev/null || true
  rm -f "$REPO_ROOT/.farol/smoke_test.key" "$MOCK_PY" "$TEST_KEY_LINK"
}
trap cleanup EXIT

# ── Temp key ─────────────────────────────────────────────────────────────────
mkdir -p "$REPO_ROOT/.farol"
GOOD_KEY="smoketestkey00000000000000000000000000000000000000"
echo "$GOOD_KEY" > "$REPO_ROOT/.farol/smoke_test.key"

# ── Write mock server Python script ──────────────────────────────────────────
cat > "$MOCK_PY" <<'PYEOF'
import sys, json, socket
from http.server import HTTPServer, BaseHTTPRequestHandler

PORT   = int(sys.argv[1])
APIKEY = sys.argv[2].strip()
MODE   = sys.argv[3]  # "pass" or "fail"

MODEL_ID = "gemma-3n-e4b-it"

class ReuseHTTPServer(HTTPServer):
    allow_reuse_address = True

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass  # suppress access log

    def _auth_ok(self):
        auth  = self.headers.get("Authorization", "")
        xkey  = self.headers.get("X-Farol-Key", "")
        token = auth.removeprefix("Bearer ").strip()
        return token == APIKEY or xkey == APIKEY

    def _send_json(self, code, body_bytes):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body_bytes)))
        self.end_headers()
        self.wfile.write(body_bytes)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, b'{"status":"ok"}')
        elif self.path == "/v1/models":
            if not self._auth_ok():
                self.send_response(401); self.end_headers(); return
            body = json.dumps({"object":"list","data":[{"id":MODEL_ID,"object":"model"}]}).encode()
            self._send_json(200, body)
        else:
            self.send_response(404); self.end_headers()

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_response(404); self.end_headers(); return
        if not self._auth_ok():
            self.send_response(401); self.end_headers(); return

        length = int(self.headers.get("Content-Length", 0))
        raw    = self.rfile.read(length)
        req    = json.loads(raw) if length else {}
        stream = req.get("stream", False)

        if MODE == "fail":
            # Return garbage — no "FAROL OK" in content
            body = json.dumps({"object":"chat.completion","choices":[{"message":{"content":"WRONG ANSWER"}}]}).encode()
            self._send_json(200, body)
            return

        if stream:
            chunks = ["FAR", "OL", " OK"]
            sse_parts = []
            for i, tok in enumerate(chunks):
                finish = "stop" if i == len(chunks) - 1 else None
                event  = {
                    "id":"chatcmpl-1","object":"chat.completion.chunk","model":MODEL_ID,
                    "choices":[{"index":0,"delta":{"content":tok},"finish_reason":finish}]
                }
                sse_parts.append(("data: " + json.dumps(event) + "\n\n").encode())
            sse_parts.append(b"data: [DONE]\n\n")
            body = b"".join(sse_parts)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        else:
            body = json.dumps({
                "id":"chatcmpl-1","object":"chat.completion","model":MODEL_ID,
                "choices":[{"index":0,"message":{"role":"assistant","content":"FAROL OK"},"finish_reason":"stop"}]
            }).encode()
            self._send_json(200, body)

ReuseHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
PYEOF

# ── Helper: start mock and set MOCK_PID ──────────────────────────────────────
start_mock() {
  local variant="${1:-pass}"
  python3 "$MOCK_PY" "$MOCK_PORT" "$GOOD_KEY" "$variant" &
  MOCK_PID=$!
}

# ── Helper: kill mock and wait for port to free ───────────────────────────────
stop_mock() {
  [[ -n "$MOCK_PID" ]] && kill "$MOCK_PID" 2>/dev/null || true
  MOCK_PID=""
  # Wait for port to be released
  for i in $(seq 1 20); do
    if ! lsof -iTCP:${MOCK_PORT} -sTCP:LISTEN -t >/dev/null 2>&1; then
      break
    fi
    sleep 0.2
  done
}

wait_for_server() {
  for i in $(seq 1 30); do
    curl -sf --max-time 1 "http://${MOCK_HOST}:${MOCK_PORT}/health" >/dev/null 2>&1 && return 0
    sleep 0.2
  done
  echo "[smoke_test] ERROR: mock server did not start on :${MOCK_PORT}" >&2
  return 1
}

# Install test key as the farol.key smoke.sh will read
install_key() {
  ln -sf "$REPO_ROOT/.farol/smoke_test.key" "$TEST_KEY_LINK"
}
remove_key() {
  rm -f "$TEST_KEY_LINK"
}

# ── POSITIVE test ─────────────────────────────────────────────────────────────
echo "=== POSITIVE TEST (mock returns correct responses) ==="
start_mock pass
wait_for_server
install_key

PIXEL_HOST="$MOCK_HOST" PORT="$MOCK_PORT" "$SMOKE"

remove_key
stop_mock

echo ""
echo "Positive test: PASSED as expected."

# ── NEGATIVE test ─────────────────────────────────────────────────────────────
echo ""
echo "=== NEGATIVE TEST (mock returns wrong completion → smoke must FAIL) ==="
start_mock fail
wait_for_server
install_key

set +e
PIXEL_HOST="$MOCK_HOST" PORT="$MOCK_PORT" "$SMOKE" 2>&1
SMOKE_EXIT=$?
set -e

remove_key
stop_mock

if [[ $SMOKE_EXIT -ne 0 ]]; then
  echo "[PASS] Negative test: smoke.sh correctly exited non-zero (exit $SMOKE_EXIT)"
else
  echo "[FAIL] Negative test: smoke.sh should have failed but exited 0" >&2
  exit 1
fi

echo ""
echo "ALL SMOKE_TEST ASSERTIONS PASSED"

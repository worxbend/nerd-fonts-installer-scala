#!/usr/bin/env bash
# Interrupt smoke test for the shipped binary (SPEC §6.8, §9).
#
# Usage: scripts/ci/interrupt-smoke.sh <path-to-nerd-fonts-installer>
#
# Starts a local HTTP stub that answers the checksum manifest with 404 and streams an endless, slow
# /latest/download/Hack.zip, points the binary at it through the NERD_FONTS_INSTALLER_BASE_URL test hook,
# sends SIGINT one second into the download and asserts:
#   * exit code 1 and the `install fonts: interrupted` line on stderr,
#   * no nerd-font-*.zip left under $TMPDIR,
#   * no .Hack-* staging directory and no Hack directory under the destination.
set -euo pipefail

if [[ $# -ne 1 || ! -x "$1" ]]; then
  echo "usage: $0 <path-to-nerd-fonts-installer>" >&2
  exit 64
fi
binary="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

work="$(mktemp -d)"
stub_pid=""
cleanup() {
  if [[ -n "${stub_pid}" ]]; then kill "${stub_pid}" 2>/dev/null || true; fi
  rm -rf "${work}"
}
trap cleanup EXIT

mkdir -p "${work}/tmp" "${work}/dest"

cat > "${work}/stub.py" <<'PY'
import sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path.endswith("/Hack.zip"):
            # No Content-Length: the body lasts until the connection closes, which it never does on its own.
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.end_headers()
            try:
                while True:
                    self.wfile.write(b"\0" * 1024)
                    self.wfile.flush()
                    time.sleep(0.05)
            except (BrokenPipeError, ConnectionResetError):
                return
        else:
            self.send_response(404)
            self.end_headers()

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
sys.stdout.write(f"{server.server_port}\n")
sys.stdout.flush()
server.serve_forever()
PY

python3 "${work}/stub.py" > "${work}/port" 2>/dev/null &
stub_pid=$!
for _ in $(seq 1 50); do
  if [[ -s "${work}/port" ]]; then break; fi
  sleep 0.1
done
port="$(cat "${work}/port")"
if [[ -z "${port}" ]]; then
  echo "stub server did not start" >&2
  exit 1
fi

cat > "${work}/config.yaml" <<YAML
release: latest
destination: ${work}/dest
refresh_font_cache: false
families:
  - Hack
YAML

# Job control gives the background binary its own process group with SIGINT at the default disposition;
# without it a non-interactive shell starts `&` jobs with SIGINT ignored and `kill -INT` would be dropped.
set -m
env NERD_FONTS_INSTALLER_BASE_URL="http://127.0.0.1:${port}" TMPDIR="${work}/tmp" \
  "${binary}" --config "${work}/config.yaml" > "${work}/stdout" 2> "${work}/stderr" &
app_pid=$!
set +m

sleep 1
if ! grep -q "Installing Nerd Font Hack" "${work}/stderr"; then
  echo "the download did not start within one second:" >&2
  cat "${work}/stderr" >&2
  kill "${app_pid}" 2>/dev/null || true
  exit 1
fi
kill -INT "${app_pid}"

set +e
wait "${app_pid}"
code=$?
set -e

failed=0
if [[ "${code}" -ne 1 ]]; then
  echo "expected exit code 1 after SIGINT, got ${code}" >&2
  failed=1
fi
if ! grep -q "^install fonts: interrupted$" "${work}/stderr"; then
  echo "expected 'install fonts: interrupted' on stderr" >&2
  failed=1
fi
if compgen -G "${work}/tmp/nerd-font-*.zip" > /dev/null; then
  echo "leftover temp zip under TMPDIR:" >&2
  ls -la "${work}/tmp" >&2
  failed=1
fi
if compgen -G "${work}/dest/.Hack-*" > /dev/null; then
  echo "leftover staging directory under the destination:" >&2
  ls -la "${work}/dest" >&2
  failed=1
fi
if [[ -e "${work}/dest/Hack" ]]; then
  echo "an interrupted download must not produce ${work}/dest/Hack" >&2
  failed=1
fi

if [[ "${failed}" -ne 0 ]]; then
  echo "--- stdout ---" >&2; cat "${work}/stdout" >&2
  echo "--- stderr ---" >&2; cat "${work}/stderr" >&2
  exit 1
fi
echo "interrupt smoke passed: exit ${code}, no temp zip, no staging dir"

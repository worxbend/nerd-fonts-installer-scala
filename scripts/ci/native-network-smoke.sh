#!/usr/bin/env bash
# Network smoke test for a shipped native binary (SPEC §9).
#
# Usage: scripts/ci/native-network-smoke.sh <path-to-nerd-fonts-installer>
#
# WHY THIS EXISTS
# ---------------
# `--version` and `--help` never start a Netty event loop, so they exit cleanly even when the binary's
# HTTP path is completely broken. `--font-names` is the only command that performs a real request without
# needing a config file (it falls back to the `latest` release when none is discovered), which makes it the
# one command that proves the network path survived native-image compilation.
#
# The specific failure this guards against: if `-H:+SharedArenaSupport` is missing from the native-image
# options (see build.mill), the binary serves the request correctly, prints the right answer, and then
# never exits -- Netty's shutdown path throws `UnsupportedFeatureError: Support for Arena.ofShared is not
# active` on every event-loop thread. Two measured consequences shape this script:
#
#   * Asserting on stdout is USELESS: the broken binary emits byte-identical, correct output. Only the
#     exit code distinguishes it, so this script requires the process to EXIT 0 inside the window.
#   * The hung process IGNORES SIGTERM (observed alive >2 minutes under a plain `timeout`). The timeout
#     must therefore escalate to SIGKILL, which is what `--kill-after` does.
#
# Exit codes from the timeout wrapper: 124 => SIGTERM took effect, 137 => SIGKILL was needed. Both mean
# the binary hung and the build is broken.
set -uo pipefail

if [[ $# -ne 1 || ! -x "$1" ]]; then
  echo "usage: $0 <path-to-nerd-fonts-installer>" >&2
  exit 64
fi
binary="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

# A healthy run is ~3s. 30s leaves ~10x headroom for GitHub API and runner network jitter while still
# failing fast on a hang; `--kill-after=5` guarantees the hung process actually dies.
timeout_seconds="${NATIVE_SMOKE_TIMEOUT:-30}"

echo "native network smoke: ${binary} --font-names (timeout ${timeout_seconds}s)"

output=""
status=0
output="$(timeout --kill-after=5 -s TERM "${timeout_seconds}" "${binary}" --font-names 2>&1)" || status=$?

if [[ ${status} -eq 124 || ${status} -eq 137 ]]; then
  echo "FAIL: binary did not exit within ${timeout_seconds}s (signal exit ${status})." >&2
  echo "      This is the Arena.ofShared hang: check that -H:+SharedArenaSupport is in nativeImageOptions." >&2
  echo "----- captured output before the hang -----" >&2
  echo "${output}" >&2
  exit 1
fi

if [[ ${status} -ne 0 ]]; then
  echo "FAIL: --font-names exited ${status}." >&2
  echo "${output}" >&2
  exit 1
fi

# Guard against a binary that exits 0 having printed nothing useful (for example a silently empty
# response or a stubbed-out command).
if ! grep -Eq '^# v[0-9]+\.[0-9]+' <<<"${output}"; then
  echo "FAIL: no release tag (expected a line like '# v3.5.1') in --font-names output." >&2
  echo "${output}" >&2
  exit 1
fi

families="$(grep -cE '^[[:space:]]+- ' <<<"${output}")"
if [[ "${families}" -lt 10 ]]; then
  echo "FAIL: expected at least 10 font families, found ${families}." >&2
  echo "${output}" >&2
  exit 1
fi

echo "PASS: native network path OK (${families} lines, exited 0)."

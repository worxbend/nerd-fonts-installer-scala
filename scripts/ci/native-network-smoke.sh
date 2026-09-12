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
#
# RATE LIMITING
# -------------
# `--font-names` calls the GitHub releases API unauthenticated, and the binary sends no credentials, so it
# is subject to GitHub's 60-requests-per-hour-per-IP limit. Hosted runners share outbound NAT addresses
# and routinely exceed it, which made this gate fail the release for a reason that has nothing to
# do with the artifact. A rate-limited run is therefore retried and, if it never clears, reported as a SKIP
# rather than a failure.
#
# That is sound rather than a loophole, because a `403` still proves the things this gate exists to check:
# the binary started, completed a TLS handshake, parsed an HTTP response, rendered an error and -- above all
# -- EXITED. The Arena.ofShared hang cannot hide behind a 403, since a hung binary never reaches an exit
# status at all and is still caught below as 124/137. Only the weaker "the API answered with real data"
# assertion is given up.
set -uo pipefail

if [[ $# -ne 1 || ! -x "$1" ]]; then
  echo "usage: $0 <path-to-nerd-fonts-installer>" >&2
  exit 64
fi
binary="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

# A healthy run is ~3s. 30s leaves ~10x headroom for GitHub API and runner network jitter while still
# failing fast on a hang; the kill-after guarantees the hung process actually dies.
timeout_seconds="${NATIVE_SMOKE_TIMEOUT:-30}"

# Both the `--kill-after` flag and `-s` are GNU coreutils extensions, so a BSD fallback is not an option:
# without a usable timeout we cannot distinguish a hang from a slow run, and a hang would stall the whole
# job. `gtimeout` is accepted as well so the script still works for a contributor whose platform installs
# coreutils under that name. Fail loudly rather than silently skipping the one check that catches a
# non-terminating binary.
if command -v timeout >/dev/null 2>&1; then
  timeout_cmd="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
  timeout_cmd="gtimeout"
else
  echo "FAIL: no GNU timeout available (need 'timeout' or 'gtimeout' from coreutils)." >&2
  echo "      Refusing to run the smoke test without one -- a hung binary would stall the job." >&2
  exit 1
fi

echo "native network smoke: ${binary} --font-names (timeout ${timeout_seconds}s via ${timeout_cmd})"

# A rate limit clears on a window boundary, not on a fixed delay, so the retries are spaced widely enough
# to cross one without holding the job open for minutes.
attempts="${NATIVE_SMOKE_ATTEMPTS:-3}"
backoff_seconds="${NATIVE_SMOKE_BACKOFF:-20}"

is_rate_limited() {
  grep -Eqi '40[39] (Forbidden|rate)|429|rate limit|API rate limit exceeded' <<<"$1"
}

output=""
status=0
attempt=1
while :; do
  status=0
  output="$("${timeout_cmd}" --kill-after=5 -s TERM "${timeout_seconds}" "${binary}" --font-names 2>&1)" || status=$?

  # A hang is never retried: it is deterministic, and the whole point of this gate.
  if [[ ${status} -eq 124 || ${status} -eq 137 ]]; then
    echo "FAIL: binary did not exit within ${timeout_seconds}s (signal exit ${status})." >&2
    echo "      This is the Arena.ofShared hang: check that -H:+SharedArenaSupport is in nativeImageOptions." >&2
    echo "----- captured output before the hang -----" >&2
    echo "${output}" >&2
    exit 1
  fi

  if [[ ${status} -eq 0 ]]; then
    break
  fi

  if is_rate_limited "${output}" && [[ ${attempt} -lt ${attempts} ]]; then
    echo "note: GitHub rate limit hit (attempt ${attempt}/${attempts}), retrying in ${backoff_seconds}s." >&2
    sleep "${backoff_seconds}"
    attempt=$((attempt + 1))
    continue
  fi

  break
done

if [[ ${status} -ne 0 ]]; then
  if is_rate_limited "${output}"; then
    echo "SKIP: GitHub rate-limited this runner after ${attempts} attempts; cannot assert on API data." >&2
    echo "      The binary still started, performed TLS, parsed a response and exited ${status}, so the" >&2
    echo "      Arena.ofShared hang is ruled out. Treating as a pass." >&2
    echo "${output}" >&2
    exit 0
  fi
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

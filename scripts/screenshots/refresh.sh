#!/usr/bin/env bash
# Regenerate assets/screenshots/*.svg from real runs of the native binary.
#
# Usage: scripts/screenshots/refresh.sh [path-to-nerd-fonts-installer]
#
# Without an argument the binary under out/app/nativeImage.dest/ is used, built with
# `./mill --no-daemon show app.nativeImage` first when it is missing. Requires python3 (standard library
# only) and, for the picker shots and the live --font-names run, access to api.github.com.
#
# Shots:
#   cli-help.svg        --help
#   cli-dry-run.svg     --config config.example.yaml --dry-run, with FORCE_COLOR=1 and HOME=/home/dev so
#                       the destination reads like an ordinary machine instead of the maintainer's home
#   cli-font-names.svg  --font-names | head -20 from a live run; when the listing cannot be fetched the
#                       checked-in fixtures/font-names.txt (itself a previous live run) is rendered instead
#   tui-releases.svg    --interactive --icons unicode in a 112x34 pty, first frame, then q
#   tui-families.svg    the same, after Enter (newest release), Space, Down, Space, Down, Space, then q
#
# Every capture is what the binary actually wrote; the one line that is not program output is the
# `$ command` prompt that opens each CLI shot. Nothing is installed: the picker is left with q before the
# install step, and the dry run touches no network or filesystem.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../.." && pwd)"
out="${repo}/assets/screenshots"
fixtures="${here}/fixtures"
binary="${1:-${repo}/out/app/nativeImage.dest/native-executable}"

command -v python3 >/dev/null || { echo "missing required tool: python3" >&2; exit 1; }

if [[ ! -x "${binary}" ]]; then
  echo "building the native binary (no argument given and ${binary} is missing)"
  (cd "${repo}" && ./mill --no-daemon show app.nativeImage >/dev/null)
fi
[[ -x "${binary}" ]] || { echo "not an executable: ${binary}" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
mkdir -p "${out}" "${work}/home" "${work}/cwd"

# A `$ command` line in the shell's green, followed by the command's own output.
prompt() {
  printf '\033[1;32m$\033[0m %s\n' "$1"
}

render() {
  python3 "${here}/render.py" "$@"
}

# --- cli-help ---------------------------------------------------------------------------------------------
{
  prompt "nerd-fonts-installer --help"
  "${binary}" --help
} > "${work}/help.txt"
render "${work}/help.txt" "${out}/cli-help.svg" --title "nerd-fonts-installer --help" --min-cols 84

# --- cli-dry-run ------------------------------------------------------------------------------------------
{
  prompt "nerd-fonts-installer --config config.example.yaml --dry-run"
  (cd "${repo}" && env HOME=/home/dev FORCE_COLOR=1 "${binary}" --config config.example.yaml --dry-run 2>&1)
} > "${work}/dry-run.txt"
render "${work}/dry-run.txt" "${out}/cli-dry-run.svg" --title "nerd-fonts-installer --dry-run"

# --- cli-font-names ---------------------------------------------------------------------------------------
# The whole listing is captured to a file first so `head` never closes the pipe under the binary; a
# successful run also refreshes the fixture that offline runs fall back to.
if (cd "${work}/cwd" && env HOME="${work}/home" "${binary}" --font-names > "${work}/font-names.full" 2> "${work}/font-names.err"); then
  head -20 "${work}/font-names.full" > "${fixtures}/font-names.txt"
  echo "font names: live run (fixture refreshed)"
else
  echo "font names: live run failed ($(tr '\n' ' ' < "${work}/font-names.err")); using ${fixtures}/font-names.txt" >&2
fi
{
  prompt "nerd-fonts-installer --font-names | head -20"
  cat "${fixtures}/font-names.txt"
} > "${work}/font-names.txt"
render "${work}/font-names.txt" "${out}/cli-font-names.svg" --title "nerd-fonts-installer --font-names" --min-cols 60

# --- tui-releases / tui-families --------------------------------------------------------------------------
# An empty HOME and cwd guarantee no config is discovered, so --interactive opens the picker; stderr is
# dropped so the "No config found" notice and the loading spinner stay out of the recording. The picker
# needs the release list from api.github.com; when that fails the two shots are left as they are.
tui() {
  local name="$1"; shift
  if (cd "${work}/cwd" && python3 "${here}/capture.py" --out "${work}/${name}.txt" --cols 112 --rows 34 \
        --stderr null --env HOME="${work}/home" --unset NERD_FONTS_INSTALLER_CONFIG --unset XDG_CONFIG_HOME \
        "$@" -- "${binary}" --interactive --icons unicode); then
    render "${work}/${name}.txt" "${out}/${name}.svg" --title "nerd-fonts-installer --interactive --icons unicode"
  else
    echo "${name}: the picker did not paint a frame (is api.github.com reachable?); keeping the existing ${out}/${name}.svg" >&2
  fi
}
tui tui-releases --send 'q'
tui tui-families --send '\r' --send ' ' --send '\x1b[B' --send ' ' --send '\x1b[B' --send ' ' --send 'q'

# --- well-formedness --------------------------------------------------------------------------------------
python3 - "${out}"/*.svg <<'PY'
import sys, xml.etree.ElementTree as ElementTree
for path in sys.argv[1:]:
    ElementTree.parse(path)
print("valid XML:", ", ".join(sys.argv[1:]))
PY
echo "screenshots written to ${out}"

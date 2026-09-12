# Parity with the Go reference

Status: recorded on 2026-09-11 against `worxbend/nerd-fonts-installer` built from source with Go 1.27
(`go build -o nfi-go ./cmd/nerd-fonts-installer`) and the GraalVM native image of this repository at the
commit that added this file (`./mill --no-daemon show app.nativeImage`, linux-amd64). Both binaries were run
with identical arguments, working directory and environment; stdout and stderr were captured to files and
compared with `diff`. Network scenarios ran against the live GitHub API and release page.

| Scenario | Go: exit / stdout / stderr | Scala: exit / stdout / stderr | Verdict |
| --- | --- | --- | --- |
| `--version` | 0 / `nerd-fonts-installer dev (none, unknown)` / — | 0 / `nerd-fonts-installer 0.1.0 (3b1a900b2f1f, unknown)` / — | Same shape `<name> <version> (<commit>, <date>)`; values are build inputs |
| `--help` | 2 / — / `Usage of nerd-fonts-installer:` + flag list | 0 / header + picocli usage + flag list / — | **Intentional deviation** (SPEC §1): usage on stdout, exit 0. Options listed in the Scala tool's order (`config, dry-run, font-names, version`) with `-h, -help, --help` appended |
| `--config unknown.yaml` (extra key `bogus`) | 1 / — / `load config <path>: parse <path>: yaml: unmarshal errors:` ⏎ `  line 4: field bogus not found in type config.Config` | 1 / — / `load config <path>: parse <path>: unknown field "bogus"` | Same prefixes and exit code; the detail is yaml.v3's two-line message versus the SPEC §3.2 `UnknownField` wording (one line, field quoted). Accepted: the spec defines the cause text as platform wording |
| no config, empty `$HOME`, empty cwd | 2 / — / `no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: <16 candidates>` | 2 / — / identical | `diff` empty: same 16 candidates in the same order |
| `--config dup.yaml` (`families: [Hack, Hack]`) | 1 / — / `load config <path>: duplicate font family "Hack"` | 1 / — / identical | Byte-identical |
| `--font-names --config pinned.yaml` (`release: v3.4.0`, live API) | 0 / `# v3.4.0` ⏎ `families:` ⏎ 72 `  - <stem>` lines / — | 0 / identical / — | `diff` empty |
| `--config config.example.yaml --dry-run` | 0 / four `• Would install …` lines + `↻ Would refresh font cache for …` / — | 0 / identical / — | `diff` empty (destination `~` expanded to the same absolute path) |
| `--bogus` | 2 / — / `flag provided but not defined: -bogus` + usage | 2 / — / `Unknown option: '--bogus'` + header + usage | Same exit code and stream; the first line is the parser's own wording (Go `flag` vs picocli). Accepted |
| real install `families: [Hack]`, `release: latest`, `refresh_font_cache: false`, temp destination | not re-run (same upstream archive) | 0 / — / `⠋ Installing Nerd Font Hack from <url>` ⏎ `✅ Installed Hack into <dest>/Hack`; 12 `.ttf` files under `<dest>/Hack/`, no `.old`, no `.Hack-*`, no `nerd-font-*.zip`; a second run replaced the directory (a marker file placed inside beforehand was gone) | Layout and atomic-replace semantics as specified in §6 |
| SIGINT one second into a download (local stub, `scripts/ci/interrupt-smoke.sh`) | Go: exit 1, `install fonts: … context canceled` (from the reference's tests, not re-run) | 1 / — / `• Checksum manifest unavailable (404 Not Found); …` ⏎ `⠋ Installing Nerd Font Hack from http://127.0.0.1:<port>/latest/download/Hack.zip` ⏎ `install fonts: interrupted`; `$TMPDIR` and the destination are empty afterwards | Exit code and cleanup identical; the message text is the documented §6.8 wording |

## Behaviour confirmed identical by the shared scenarios

- Exit codes: 0 / 1 / 2 map to the same categories as Go for the remaining config-driven surface (`--help` excepted, above).
- Operation prefixes: `load config <path>: `, `load discovered config <path>: `, `install fonts: `.
- Config discovery order, candidate de-duplication and the hint text.
- `--font-names` output format and the raw asset stems, including `Go-Mono`, `iA-Writer` and `FontPatcher`.
- Dry-run plan lines: glyphs, URL shapes, `<root>/<Family>` targets, the `↻` cache line.
- Temp-file location: both stage `nerd-font-*.zip` under `$TMPDIR` (Go `os.CreateTemp("", …)`; here
  `Cli.tempDir`, which falls back to `java.io.tmpdir` when the variable is unset).

## Deviations kept on purpose

| Deviation | Why |
| --- | --- |
| `--help` → stdout, exit 0 | SPEC §1; `--help \| less` is what people do, Go's exit 2 is a `flag` artefact |
| Parser-generated wording for a malformed command line and for a YAML unknown key | Not machine-parsed; the prefix, stream and exit code — which scripts check — match |
| Terminal selection feature is absent | Product decision: the Scala port is config-file-driven only |
| Second SIGINT halts with 130 | Escape hatch for a cleanup that hangs; Go absorbs repeats (SPEC §6.8) |

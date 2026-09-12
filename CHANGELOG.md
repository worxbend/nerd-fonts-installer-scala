# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The version is `Versions.project` in
`build.mill`; releases are Git tags `vX.Y.Z` built by `.github/workflows/release.yml`.

## [Unreleased]

### Added

- `docs/SECURITY.md` (threat model, controls, reporting), `CONTRIBUTING.md`, GitHub issue forms and a pull
  request template.
- HOCON configuration support, including the `.hocon` extension, which joins `yaml`, `yml`, `json` and
  `conf` in the discovery order.

### Changed

- **Breaking:** `.conf` files are now parsed as HOCON rather than YAML. `.conf` is HOCON's conventional
  extension, and the previous mapping was a wart. A flat `key: value` file parses identically under both,
  but a YAML block sequence (`- JetBrainsMono`) is not valid HOCON and must be rewritten as
  `families = ["JetBrainsMono"]` or given a `.yaml` extension.
- **Breaking:** A config file whose extension is missing or unrecognised is now a hard error (exit 1, the
  same as every other config-load failure) instead of being decoded as YAML. Silently guessing the format
  hid typos such as `config.yam`.
- `.json` files are decoded by Typesafe Config rather than a strict JSON parser. HOCON is a superset of
  JSON, so every valid JSON document still parses; the practical difference is that JSON-with-comments and
  unquoted keys are now accepted instead of rejected.
- **Breaking:** Unrecognised keys in a config file are now ignored instead of rejected. Previously an extra
  key failed the load with `unknown field "bogus"`; zio-config accepts it and decodes the keys it knows.
  A consequence worth knowing: a misspelled key is silently ignored, so `familes:` no longer fails the run —
  it falls back to the default for `families` and the mistake surfaces as "at least one font family is
  required" rather than as a spelling error.
- **Breaking:** A scalar where a list is expected is now accepted. `families: FiraCode` decodes as
  `["FiraCode"]` instead of failing with `field "families" must be a list`.
- Rebuilt on ZIO 2: effects, concurrency and resource safety now go through `ZIO`, HTTP through zio-http
  and configuration decoding through zio-config. Toolchain moved to Scala 3.9, GraalVM for JDK 25.0.2 and
  Mill 1.1.9.

### Removed

- **Breaking:** Removed the terminal selection mode and icon-set flag; installs are now config-file-driven only, and
  a run with no discoverable config exits 2 with the existing no-config hint.
- **Breaking:** Single-dash long flags (`-config`, `-dry-run`) are no longer accepted; use the double-dash
  forms (`--config`, `--dry-run`). A single-dash long flag is now rejected
  with exit 2 rather than being silently ignored, which previously risked `-dry-run` performing a real
  installation.

## [0.1.0] - 2026-09-11

Initial release: a Scala 3 CLI that installs Nerd Fonts from a declarative config, shipped as GraalVM
native binaries that need no JVM.

### Added

- Declarative installs from a YAML, JSON or `.conf` config (`release`, `destination`, `refresh_font_cache`,
  `families`), with defaults, strict keys and validation messages, and the discovery
  order: `--config`, `$NERD_FONTS_INSTALLER_CONFIG`, app-named files in the working directory, then under
  `$XDG_CONFIG_HOME` or `~/.config`.
- `--dry-run` prints the plan (URL and target per family, the font-cache line) without touching the network
  or the disk; `--font-names` prints YAML-ready family names for the configured or latest release.
- The install engine: up to four families at once, each downloaded to a temp file while its SHA-256 is
  computed, verified against the release's `SHA-256.txt` (a missing manifest warns, a mismatch is fatal),
  extracted (only `.ttf`/`.otf`/`.ttc`, flattened, byte-capped) into a staging directory and renamed
  atomically over `<destination>/<Family>` with the previous directory kept as `.old` until the swap
  commits. First failure cancels the in-flight siblings; finished families stay installed.
- Optional `fc-cache -f <destination>` afterwards, skipped with a warning when `fc-cache` is not on `PATH`.
- Byte caps against oversized or hostile archives: 768 MiB per download, 128 MiB per font file, 2 GiB per
  archive, 1 MiB manifest, 8 MiB per API page.
- SIGINT handling that unwinds cleanly: in-flight downloads abort, temp files and staging directories are
  removed, the terminal is restored, exit 1 with `install fonts: interrupted`; a second SIGINT halts with 130.
- Exit codes: 0 success; 2 for user-correctable input
  (malformed flags, no config, unknown release, no releases); 1 for everything else.
- Every flag accepted with a single dash as well (`-config`, `-dry-run`, …); `--version` prints
  `nerd-fonts-installer <version> (<commit>, <date>)`.
- Colour handling through `NO_COLOR`, `TERM=dumb`, `CLICOLOR_FORCE` and `FORCE_COLOR`.
- Native binaries for linux-amd64, linux-arm64, macos-amd64 and macos-arm64, each in a `tar.gz` with a
  SHA-256 entry in `checksums.txt`; `scripts/install.sh` downloads, verifies and installs the right one into
  `~/.local/bin`; a moving `latest` pre-release tracks `main`.
- CI: format, scalafix, compile with `-Werror` and tests on Ubuntu and macOS; a native-image smoke job on
  linux-amd64 including the interrupt test against a local stub server; actionlint.

### Changed

- `--help` prints usage to stdout and exits 0. This is recorded in `docs/SPEC.md`.

[Unreleased]: https://github.com/worxbend/nerd-fonts-installer-scala/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/worxbend/nerd-fonts-installer-scala/releases/tag/v0.1.0

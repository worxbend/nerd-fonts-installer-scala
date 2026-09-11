# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). The version is `Versions.project` in
`build.mill`; releases are Git tags `vX.Y.Z` built by `.github/workflows/release.yml`.

## [Unreleased]

### Added

- `docs/SECURITY.md` (threat model, controls, reporting), `CONTRIBUTING.md`, GitHub issue forms and a pull
  request template.

## [0.1.0] - 2026-09-11

Initial release: a Scala 3 re-implementation of the Go
[`worxbend/nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer) with the same
user-facing behaviour, shipped as GraalVM native binaries that need no JVM.

### Added

- Declarative installs from a YAML, JSON or `.conf` config (`release`, `destination`, `refresh_font_cache`,
  `families`), with Go-identical defaults, strict keys and validation messages, and the same discovery
  order: `--config`, `$NERD_FONTS_INSTALLER_CONFIG`, app-named files in the working directory, then under
  `$XDG_CONFIG_HOME` or `~/.config`.
- `--dry-run` prints the plan (URL and target per family, the font-cache line) without touching the network
  or the disk; `--font-names` prints YAML-ready family names for the configured or latest release.
- An interactive terminal picker (`--interactive`) with release and family steps, fuzzy filtering,
  select-all, and `--icons auto|nerd|unicode|ascii`; cancelling exits 0.
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
- Exit codes matching the Go `exitCodeFor`: 0 success or picker cancelled; 2 for user-correctable input
  (malformed flags, invalid `--icons`, no config, `--interactive` without a terminal, unknown release, no
  releases); 1 for everything else.
- Every flag accepted with a single dash as well (`-config`, `-dry-run`, …); `--version` prints
  `nerd-fonts-installer <version> (<commit>, <date>)`.
- Colour handling through `NO_COLOR`, `TERM=dumb`, `CLICOLOR_FORCE` and `FORCE_COLOR`.
- Native binaries for linux-amd64, linux-arm64, macos-amd64 and macos-arm64, each in a `tar.gz` with a
  SHA-256 entry in `checksums.txt`; `scripts/install.sh` downloads, verifies and installs the right one into
  `~/.local/bin`; a moving `latest` pre-release tracks `main`.
- CI: format, scalafix, compile with `-Werror` and tests on Ubuntu and macOS; a native-image smoke job on
  linux-amd64 including the interrupt test against a local stub server; actionlint.

### Changed

- Compared with the Go reference, `--help` prints usage to stdout and exits 0 (Go: stderr, exit 2), and the
  picker's `h`/`l`/`f`/`d`/`u` paging aliases are not bound. Both are recorded in `docs/SPEC.md` and
  `docs/PARITY.md`.

[Unreleased]: https://github.com/worxbend/nerd-fonts-installer-scala/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/worxbend/nerd-fonts-installer-scala/releases/tag/v0.1.0

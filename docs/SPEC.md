# nerd-fonts-installer-scala — implementation specification

Status: authoritative design for v0.1.0. Implementation agents follow this document; deviations must
be justified in the PR description and reflected back here.

## 1. Goal

A Scala 3 re-implementation of [`worxbend/nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer)
(Go) with identical user-facing behaviour, shipped as a GraalVM native binary for Linux (amd64, arm64)
and macOS (amd64, arm64).

The tool installs [Nerd Fonts](https://github.com/ryanoasis/nerd-fonts) from a declarative config file
or an interactive terminal picker: resolve a release, download one zip per font family from GitHub,
verify it against the release's `SHA-256.txt`, extract only font files into `<destination>/<Family>/`
atomically, optionally run `fc-cache`.

Behavioural parity targets (must match the Go tool byte-for-byte where output is machine-readable):

- Flags: `--config <path>`, `--dry-run`, `--font-names`, `--interactive`, `--icons <auto|nerd|unicode|ascii>`,
  `--version`, `-h/--help`.
- `--font-names` stdout format:
  ```
  # v3.4.0
  families:
    - 0xProto
    - 3270
  ```
- Exit codes: `0` success **or user cancelled the picker**; `2` user-correctable input (invalid flags, invalid
  `--icons`, no config found, config load/validation error, unknown release tag, no releases at all,
  `--interactive` without a terminal); `1` runtime failure (network, filesystem, extraction, checksum mismatch,
  `fc-cache` failure).
- Config discovery order and env var `NERD_FONTS_INSTALLER_CONFIG` (see §4).
- Install layout and atomic replacement semantics (see §6).

## 2. Technology and conventions

| Concern | Choice |
| --- | --- |
| Language / build | Scala 3.8.4, Mill 1.1.7 (`./mill`), JVM toolchain `graalvm-community:25.0.1` fetched by Mill (`jvmVersion` on every module, so `app.nativeImage` finds `native-image` without `GRAALVM_HOME`); bytecode target 21 |
| Concurrency | Ox 1.0.6 (`Flow.mapParUnordered`, `supervised`, `timeout`, `abandonOnInterruptReads`) — direct style, no Futures |
| CLI | picocli 4.7.7 (reflection config maintained by hand in `app/resources/META-INF/native-image/...` and asserted by a test) |
| YAML / JSON | `org.virtuslab::scala-yaml` (AST only) and `ujson` (AST only). No derivation, no reflection |
| Filesystem | `os-lib` for paths and IO; `java.util.zip.ZipInputStream` for archives |
| HTTP | `java.net.http.HttpClient` behind a port |
| Colour | `fansi` |
| Tests | munit 1.3.6 + munit-scalacheck 1.3.1 |
| Lint | scalafmt 3.11.5, scalafix (OrganizeImports, DisableSyntax: no `var` fields, no `return`, no `null`, no `while`) |
| Compiler | `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement -Wsafe-init -source:future` |

Coding rules (from the direct-style Scala skill and Refactoring Guru; enforced in review):

- Braceless Scala 3 syntax everywhere. Explicit return types on every public member.
- Every top-level type declares intentional visibility: public API, `private[<pkg>]`, or `private[nerdfonts]`.
- Domain values are opaque types or enums, never raw `String`/`Boolean` (`FamilyName`, `ReleaseTag`,
  `ReleaseSelector`, `IconMode`, `DryRun`, `RefreshFontCache`, `ByteLimit`, `Sha256Digest`).
- Recoverable failures are `Either[E, A]` with sealed error ADTs per concern. Exceptions only cross a
  boundary as defects or as the internal cancellation signal described in §6.5.
- No class-level `var`. State machines are immutable case classes with pure `update` functions
  (the picker model is the canonical example).
- Side effects live behind small port traits (`HttpClient`, `FontCacheRefresher`, `Terminal`,
  `Environment`, `ProcessRunner`), with in-memory fakes in tests.
- Functions do one thing; orchestration reads as a sequence of named steps. Prefer `either:` blocks with
  `.ok()` over nested `flatMap` chains when three or more steps compose.
- Comments explain *why* (invariants, security reasoning, platform quirks), not *what*.
- Tests are targeted: one scenario per test, named as a sentence.

## 3. Module and package map

Mill modules and their allowed dependency edges (enforced by `moduleDeps`):

```
app -> cli -> { core, config, picker }
config -> core
picker -> core
```

Root package: `io.worxbend.nerdfonts`. Packages are named after concepts, not mechanisms.

### 3.1 `core` module

| Package | Owns |
| --- | --- |
| `io.worxbend.nerdfonts.fonts` | `FamilyName` (validated opaque type — **the single path-traversal guard**), `ReleaseTag`, `ReleaseSelector` (`Latest \| Tagged(tag)`), `FontSet`/`InstallConfig` (validated config model), `DestinationPath`, `RefreshFontCache` enum, `ConfigValidationError` |
| `io.worxbend.nerdfonts.releases` | `Release(name, tag, families)`, `ReleaseCatalogue` port (`def releases(): Either[ReleaseError, Vector[Release]]`), `GitHubReleaseCatalogue` adapter (paginated GitHub API client), `ReleaseError` ADT (`NoReleases`, `NotFound(tag)`, `Http(...)`, `Decode(...)`), `ReleaseUrls` (download / checksum URL builders with path escaping), `ChecksumManifest` (parse `SHA-256.txt` → `Map[FamilyName, Sha256Digest]`), `Sha256Digest` opaque type |
| `io.worxbend.nerdfonts.http` | `HttpClient` port (`get(url, maxBytes): Either[HttpError, HttpBody]` where a body is streamed to a sink with a byte cap), `JdkHttpClient` adapter (redirects `NORMAL`, 30 s connect timeout, `User-Agent: nerd-fonts-installer`), `HttpError` ADT (`Transport`, `Status(code)`, `TooLarge(limit)`) |
| `io.worxbend.nerdfonts.install` | `InstallRequest`, `InstallPlan` (pure: what would happen), `FontInstaller` (the engine), `ArchiveExtractor`, `DirectorySwap` (atomic replace with `.old` backup), `FontCacheRefresher` port + `FcCacheRefresher` adapter, `InstallEvent` ADT + `InstallEventSink`, `InstallError` ADT, `SizeLimits` |
| `io.worxbend.nerdfonts.paths` | `Environment` port (`variable`, `homeDirectory`, `workingDirectory`) with `Environment.System` and `Environment.fixed`, `PathExpander` (`~` expansion) |
| `io.worxbend.nerdfonts.process` | `ProcessRunner` port (`run(command: Vector[String], stdout, stderr): Either[ProcessError, ExitStatus]`, `lookPath(name): Option[os.Path]`), `JdkProcessRunner` adapter |

### 3.2 `config` module — `io.worxbend.nerdfonts.config`

`ConfigLoader.load(path): Either[ConfigError, InstallConfig]`, `ConfigDocument` (raw decoded shape before
defaults), `YamlConfigDecoder`, `JsonConfigDecoder`, `ConfigDiscovery` (candidate paths + first-hit loading),
`ConfigLocations` (the ordered candidate list), `ConfigError` ADT (`NotFound(path)`, `Unreadable(path, cause)`,
`Parse(path, message)`, `UnknownField(path, field)`, `WrongType(path, field, expected)`,
`Invalid(path, ConfigValidationError)`).

### 3.3 `picker` module — `io.worxbend.nerdfonts.picker`

`PickerModel` (immutable state machine), `PickerStep` (`ChooseRelease`, `ChooseFamilies`, `Done`),
`PickerKey` (decoded key events), `PickerView` (renders a `Frame` = `Vector[String]` for a `Viewport(width, height)`),
`IconMode` enum + `IconSet` (icon tables from the Go tool), `FamilyHint`, `Terminal` port
(`enterRawMode`, `leaveRawMode`, `size`, `readKey`, `write`), `SttyTerminal` adapter (uses `stty` via
`ProcessRunner`, reads keys from `System.in` wrapped in `abandonOnInterruptReads`), `PickerSession.run(...)`
(drives model ↔ terminal loop), `PickerOutcome` (`Selected(InstallConfig)` \| `Cancelled`), `Palette`
(neon palette + gradient helper), `ReleaseLoadingSpinner`.

### 3.4 `cli` module — `io.worxbend.nerdfonts.cli`

`Cli` (picocli root command, injectable writers and `AppDependencies`), `AppDependencies` (function-typed
seams: `loadConfig`, `discoverConfig`, `listReleases`, `runPicker`, `installFonts`, `isTerminal`; with
`AppDependencies.production(...)`), `Application` (the orchestration: `resolveConfig`, `printFontNames`,
`selectRelease`, `install`), `AppFailure` (top-level failure ADT wrapping module errors + `NoConfig(hint)`,
`NotATerminal`, `Cancelled`), `ExitCode` (the only place failures become POSIX codes), `OutputStyle`
(ANSI vs plain; `NO_COLOR`, `TERM=dumb`, `CLICOLOR_FORCE`/`FORCE_COLOR`), `ConsoleEventRenderer`
(renders `InstallEvent`s with the same wording as the Go tool), `BuildInfo` (generated), `TerminalProbe`
(`sh -c 'test -t 0 -a -t 1'`, because `System.console()` is non-null on JDK 22+ even when redirected).

### 3.5 `app` module — `io.worxbend.nerdfonts.app`

`Main` only. Plus `app/resources/META-INF/native-image/io.worxbend/nerd-fonts-installer/reflect-config.json`
and a test asserting every `@Command`-annotated class in `cli` is listed there.

## 4. Configuration

Schema (YAML, JSON, or `.conf` parsed as YAML), strict: unknown keys are errors.

| Key | Required | Default | Notes |
| --- | :-: | --- | --- |
| `release` | no | `latest` | `latest` or a tag such as `v3.4.0`; trimmed; blank after trim is an error |
| `destination` | no | `~/.local/share/fonts/NerdFonts` | trimmed; blank after trim is an error; `~` expanded at install time |
| `refresh_font_cache` | no | `false` | boolean |
| `families` | yes | — | list of strings; each trimmed then validated by `FamilyName`; duplicates are an error |

JSON: a single top-level object; trailing content after the first value is an error (`multiple json values`).

Resolution order (highest first), implemented in `Application` + `ConfigLocations`:

1. `--config <path>` (explicit; load errors are fatal with exit 2)
2. `$NERD_FONTS_INSTALLER_CONFIG` (trimmed; blank falls through; treated exactly like `--config`)
3. `./nerd-fonts-installer.{yaml,yml,json,conf}`
4. `./nerd-fonts-installer/config.{yaml,yml,json,conf}`
5. Same two shapes under `$XDG_CONFIG_HOME` when it is an absolute path, else under `~/.config`

Discovery loads the first candidate that exists; a candidate that exists but fails to load is a fatal
error (not skipped). When a discovered config is used, stderr gets `Using config <path>`.

`FamilyName.parse(raw)` rejects: empty, `.`, `..`, any `/` or `\`, NUL, absolute paths, and anything
whose base name differs from itself. Error messages: `font family names cannot be empty`,
`unsafe font family name "<name>"`. Config validation errors: `release is required`,
`destination is required`, `at least one font family is required`, `duplicate font family "<name>"`.

## 5. Release catalogue

`GitHubReleaseCatalogue` GETs `https://api.github.com/repos/ryanoasis/nerd-fonts/releases?per_page=100&page=N`
for N = 1..maxPages (default 5), headers `Accept: application/vnd.github+json`, `User-Agent: nerd-fonts-installer`.
Stop when the **raw** page is empty (not when filtering emptied it). Drop drafts and blank tags. Families =
sorted unique asset names ending in `.zip` (case-insensitive) minus the extension; drop releases with no
families. Release `name` falls back to the tag. Empty result → `ReleaseError.NoReleases`
(`no Nerd Fonts releases found`). Unknown tag → `ReleaseError.NotFound(tag)` (`nerd fonts release "<tag>" was not found`).

`selectRelease(releases, selector)`: `Latest` → first element; `Tagged(t)` → first with matching tag.

URL builders (path segments percent-escaped like Go's `url.PathEscape`):

- latest: `https://github.com/ryanoasis/nerd-fonts/releases/latest/download/<Family>.zip`
- tagged: `https://github.com/ryanoasis/nerd-fonts/releases/download/<tag>/<Family>.zip`
- checksums: same two shapes with `SHA-256.txt`

`ChecksumManifest.parse(text)`: each line `<hex>  <file>`; keep only `.zip` entries; digest lowercased;
key is the file name without extension parsed as `FamilyName` (skip lines that fail to parse). Read at most
1 MiB. Manifest fetch failure is a **warning**, digest mismatch is **fatal** — never weaken that.

## 6. Install engine (`FontInstaller`)

### 6.1 Inputs

`InstallRequest(selector: ReleaseSelector, destination: DestinationPath, families: Vector[FamilyName],
refreshFontCache: RefreshFontCache, dryRun: DryRun)`. Families are de-duplicated preserving order before
work starts (defensive, config already rejects duplicates).

### 6.2 Dry run

Emit `InstallEvent.WouldInstall(family, url, targetDir)` per family and `WouldRefreshCache(root)` when
enabled. Nothing touches the network or the disk. Console rendering:

```
• Would install <Family> from <url> into <root>/<Family>
↻ Would refresh font cache for <root>
```

### 6.3 Per-family pipeline

1. `Installing Nerd Font <Family> from <url>` (event `Started`).
2. Download to a temp file under the system temp dir, streaming through SHA-256, capped at
   `SizeLimits.download` (768 MiB) — reject early on `Content-Length` and again on the stream (cap + 1 byte trick).
3. If a digest is known for the family and differs: `InstallError.ChecksumMismatch(family, actual, expected)`,
   rendered `checksum mismatch for <Family>: downloaded sha256 <got>, expected <want>`.
4. Create a unique staging dir `<root>/.<Family>-<random>`; extract only `.ttf/.otf/.ttc` entries (flattened to
   base name), skipping directories, enforcing `SizeLimits.fontFile` (128 MiB) per entry (declared size first,
   then the actual stream) and `SizeLimits.archive` (2 GiB) total. Zero font files → error `no font files found`.
   Written files are flushed (`fsync`) and closed explicitly; a close error fails the install.
5. `DirectorySwap.replace(staging, target)`: remove stale `<target>.old`, rename existing target to `.old`,
   rename staging into place (roll back on failure), then best-effort delete `.old` — a cleanup failure after the
   swap is **never** reported as an install failure.
6. `Installed <Family> into <target>` (event `Installed`).
7. Temp zip and staging dir are always removed (`finally`).

Each family has a 10-minute deadline (`ox.timeout`).

### 6.4 Concurrency

Families install through `Flow.fromIterable(families).mapParUnordered(min(4, n))(installFamily).runDrain()`.
Paths per family are disjoint (`<root>/<Family>`, its own staging dir, its own `.old`), which is what makes
this safe. Progress lines are whole-line writes serialised by the event sink (an `Actor` or a sink that owns a
single writer and is invoked from one place) so lines never interleave.

### 6.5 Error propagation

A failing family must cancel in-flight siblings (Go `errgroup` semantics). Inside the flow, a `Left` from
`installFamily` is raised as the private `FamilyInstallAborted(error)` exception; `FontInstaller.install`
catches exactly that type at its boundary (`.catching[FamilyInstallAborted]`) and returns
`Left(InstallError.Family(family, error))`. This is the one sanctioned use of an exception for a recoverable
error and it never escapes the module. Already-completed families stay installed.

### 6.6 Font cache

When `refreshFontCache` is on and not dry-run: if `fc-cache` is not on `PATH`, emit warning
`fc-cache is not available; skipping font cache refresh.` and succeed. Otherwise run `fc-cache -f <root>` with
inherited stdout/stderr, emitting `Refreshing font cache for <root>` and `Font cache refreshed`. A non-zero exit is
`InstallError.FontCache(...)` → exit 1.

### 6.7 Checksum fetch

Fetched once before the fan-out via the same `HttpClient` (1 MiB cap). Any failure → warning
`Checksum manifest unavailable (<cause>); installing without integrity verification.` and an empty map.

## 7. Interactive picker

Entered only when: no explicit/discovered config, `--interactive` given, and `TerminalProbe` says both stdin and
stdout are terminals. Otherwise: no config + not interactive → exit 2 with
`no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: <candidates joined by ", ">`;
interactive but not a terminal → exit 2 `no config found; --interactive requires stdin and stdout terminals`.

Before the picker: stderr `No config found. Starting interactive mode...`, then releases are loaded while a
spinner line animates on stderr (`⠋ Loading Nerd Fonts releases` → `✓ Releases loaded`).

Model (pure, fully unit-tested without a terminal):

- Steps: `ChooseRelease` → `ChooseFamilies` → `Done`.
- Keys: `Up/Down` (and `k/j` outside filter input) move; `/` opens filter input; typing edits the filter;
  `Enter` in filter input applies it, `Esc` clears it; `Enter` chooses release / confirms families (ignored when
  none selected); `Space` toggles; `a` selects all or clears all; `b`/`Esc` go back to releases; `q`/`Ctrl-C`
  cancel (Esc on the release step also cancels).
- Result: `Cancelled` when cancelled **or finished with nothing selected**; otherwise
  `Selected(InstallConfig(tag, destination "~/.local/share/fonts/NerdFonts", refreshFontCache = on, families sorted))`.
- Layout budget mirrors the Go tool: banner box, list panel, optional side panel (width ≥ 104), footer help;
  the rendered frame **never exceeds the viewport height** (test across the same size matrix as Go:
  40×10, 60×20, 80×24, 104×25, 100×30, 112×34, 160×50, 200×60) with floors of 48 columns and 24 rows.
- Icon sets and Nerd-family glyph table are copied verbatim from the Go `icons.go`. `auto` == `unicode`.
- Rendering is a full-frame redraw per key using the alternate screen (`ESC[?1049h` / `ESC[?1049l`), hidden
  cursor, and `ESC[H` + clear-to-end-of-screen. Colours go through `fansi` true-colour and are disabled when the
  `OutputStyle` is plain.

`SttyTerminal`: `stty -g < /dev/tty` saves settings, `stty raw -echo < /dev/tty` enters raw mode, saved
settings are restored in a `finally`. Size comes from `stty size < /dev/tty` on every frame (fallback 80×24).
Keys are decoded from bytes (`ESC [ A/B/C/D`, `ESC` alone = Escape, `0x03` = Ctrl-C, `0x0d`/`0x0a` = Enter,
`0x7f`/`0x08` = Backspace, `0x20` = Space, printable UTF-8 = `Char`). Reads use
`ox.abandonOnInterruptReads(System.in)` so a scope cancellation never deadlocks on stdin.

## 8. CLI

Single root command `nerd-fonts-installer`, no subcommands, `sortOptions = false`, custom header.
`mixinStandardHelpOptions` is **off**: the tool owns `--version` (Go prints its own format, and picocli's mixin
would clash with an option of the same name — verified: the clash silently drops the mixin and `--help` stops
working), so declare `-h, --help` explicitly with `usageHelp = true` and `--version` as a plain flag. `--version`
prints `nerd-fonts-installer <version> (<commit>, <date>)`; commit/date come from `BuildInfo` (populated by
Mill from `git rev-parse --short=12 HEAD` and `NERD_FONTS_INSTALLER_BUILD_DATE` env or `unknown`).

Picocli binds options through setters, so command classes are the one sanctioned place for `var` fields; annotate
each such class with `@SuppressWarnings(Array("scalafix:DisableSyntax.var"))` and a one-line reason. Keep the
mutable surface to the command class: it snapshots its fields into an immutable `CliOptions` value that
`Application` consumes.

Flow in `Application.run(args)`:

1. Parse flags; picocli usage errors → exit 2 (`CommandLine.ExitCode.USAGE`).
2. `--version` → print, exit 0.
3. `--font-names` → resolve release from explicit/env/discovered config (default `latest`), list releases,
   select, print, exit 0; errors: exit 2 for `NotFound`/`NoReleases`/config problems, else 1.
4. Resolve config (explicit → discovered → picker) and install (or dry run).

All informational progress goes to **stderr**; stdout carries only machine-readable output (`--font-names`,
dry-run plan lines, `fc-cache` output). Error lines are the bare message (Go prints `%v`), prefixed
`install fonts: ` for install failures.

## 9. Testing strategy

- `core`: property tests for `FamilyName` (anything accepted is a single benign path component),
  `ChecksumManifest`, `ReleaseUrls`; installer tests with an in-memory `HttpClient` serving generated zips in
  temp dirs (success, multi-family, one failure cancels others, keeps existing dir on extraction failure, checksum
  mismatch fatal, missing manifest warns, oversize download / entry / total rejected, dry-run touches nothing,
  duplicate families collapsed, rollback when final rename fails).
- `config`: defaults, trimming, unknown field, wrong type, JSON, blank-after-trim, duplicates, discovery order,
  XDG handling, existing-but-broken candidate is fatal.
- `picker`: model transitions for every key, result mapping, frame height budget matrix, icon tables.
- `cli`: golden tests through `Cli.run` with fake dependencies for every exit-code path, env override, discovered
  config message, cancellation = 0, `--font-names` formats, invalid `--icons`.
- `app`: reflection config covers all picocli command classes; `Main` loads.
- CI additionally smoke-tests the native binary: `--version`, `--help`, `--dry-run` with a sample config against a
  stub (or `--font-names` skipped offline).

## 10. Deliverables outside the code

`README.md` (best-in-class, modelled on the Go README but truthful for this implementation), `docs/ARCHITECTURE.md`
(module map, invariants, decision log), `docs/SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`,
`config.example.yaml`, `scripts/install.sh`, `.github/workflows/checks.yml` (fmt, scalafix, compile, tests on
ubuntu + macos), `.github/workflows/release.yml` (4 native images, tar.gz + sha256, GitHub Release on `v*` tags and
a moving `latest` pre-release), `.github/dependabot.yml`, `AGENTS.md` + `CLAUDE.md` for future agents.

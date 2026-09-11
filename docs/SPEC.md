# nerd-fonts-installer-scala — implementation specification

Status: authoritative design for v0.1.0, revised after a three-lens adversarial review (parity with the Go
reference, direct-style Scala / native-image feasibility, structure). Implementation agents follow this
document; deviations must be listed in the commit/PR description and reflected back here.

## 1. Goal

A Scala 3 re-implementation of [`worxbend/nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer)
(Go) with identical user-facing behaviour, shipped as a GraalVM native binary for Linux (amd64, arm64)
and macOS (amd64, arm64).

The tool installs [Nerd Fonts](https://github.com/ryanoasis/nerd-fonts) from a declarative config file
or an interactive terminal picker: resolve a release, download one zip per font family from GitHub,
verify it against the release's `SHA-256.txt`, extract only font files into `<destination>/<Family>/`
atomically, optionally run `fc-cache`.

Parity targets (byte-for-byte where output is machine-readable; same wording elsewhere):

- Flags: `--config <path>`, `--dry-run`, `--font-names`, `--interactive`, `--icons <auto|nerd|unicode|ascii>`,
  `--version`, `-h/--help`. Every flag is also accepted with a single dash (`-config`, `-dry-run`, …), as Go's
  `flag` package does. Deliberate deviation: `--help` prints usage to **stdout** and exits **0** (Go's `flag`
  prints to stderr and exits 2 only as a stdlib artefact).
- `--font-names` stdout format:
  ```
  # v3.4.0
  families:
    - 0xProto
    - 3270
  ```
- Exit codes: `0` success **or user cancelled the picker**; `2` user-correctable input (malformed flags, invalid
  `--icons`, no config found, `--interactive` without a terminal, unknown release tag, no releases at all);
  `1` everything else, **including config load/parse/validation errors** (explicit `--config`,
  `$NERD_FONTS_INSTALLER_CONFIG`, or a discovered candidate that exists but fails to load), network, filesystem,
  extraction, checksum mismatch, `fc-cache` failure, interruption. This mirrors Go's `exitCodeFor`, which maps
  only `ReleaseNotFoundError`, `ErrNoReleases` and `errNoConfig` to 2.
- Config discovery order and the `NERD_FONTS_INSTALLER_CONFIG` env var (§4).
- Install layout and atomic replacement semantics (§6).

## 2. Technology and conventions

| Concern | Choice |
| --- | --- |
| Language / build | Scala 3.8.4, Mill 1.1.7 (`./mill`). JVM toolchain `graalvm-community:25.0.1` fetched by Mill (`jvmVersion` on every module, so `app.nativeImage` finds `native-image` without `GRAALVM_HOME`). Bytecode/API target = the toolchain (25): nothing ships as a JVM jar, so JDK 22+ APIs such as `Console.isTerminal` are available |
| Concurrency | Ox 1.0.6 (`Flow.mapParUnordered`, `supervised`, `Actor`, `timeoutEither`, `timeoutOption`, `abandonOnInterruptReads`) — direct style, no Futures |
| CLI | picocli 4.7.7 (reflection config maintained by hand in `app/resources/META-INF/native-image/...` and asserted by a test) |
| YAML / JSON | `org.virtuslab::scala-yaml` (AST only) and `ujson` (AST only). No derivation, no reflection |
| Filesystem | `os-lib` for paths and IO; `java.util.zip.ZipInputStream` for archives |
| HTTP | `java.net.http.HttpClient` behind a loan-shaped port (§3.1) |
| Colour | `fansi` |
| Tests | munit 1.3.6 + munit-scalacheck 1.3.1 |
| Lint | scalafmt 3.11.5, scalafix (OrganizeImports, DisableSyntax: no `var`, no `return`, no `null`, no `while`) |
| Compiler | `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement -Wsafe-init -source:future` |

Coding rules (from the direct-style Scala skill and Refactoring Guru; enforced in review):

- Braceless Scala 3 syntax everywhere. Explicit return types on every public member.
- Every top-level type declares intentional visibility: public API, `private[<pkg>]`, or `private[nerdfonts]`.
- Domain values are opaque types or enums, never raw `String`/`Boolean` (`FamilyName`, `ReleaseTag`,
  `ReleaseSelector`, `IconMode`, `DryRun`, `RefreshFontCache`, `ByteLimit`, `Sha256Digest`, `ColourMode`).
  The one sanctioned raw `String` is `Release.families` (§5): untrusted upstream asset stems shown verbatim.
- Recoverable failures are `Either[E, A]` with sealed error ADTs per concern. Exceptions cross a boundary only as
  defects, as `InterruptedException` (§6.8), or as the internal cancellation signal of §6.5.
- No class-level `var`. The single exception is picocli option binding: one `private[cli]` annotated command
  class whose option fields carry `@SuppressWarnings(Array("scalafix:DisableSyntax.var"))` with a one-line reason;
  the class does nothing but collect values into an immutable `CliOptions`. State machines (the picker model) are
  immutable case classes with pure `update` functions; local `var`s inside a method body are acceptable only for
  a fold the compiler cannot express more clearly, and never with `while` (use `@tailrec` recursion or
  `Iterator`/`repeatWhile`).
- Side effects live behind small port traits (`HttpClient`, `FontCacheRefresher`, `Terminal`, `Environment`,
  `ProcessRunner`, `InstallEventSink`), with in-memory fakes in tests.
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

Root package: `io.worxbend.nerdfonts`. Packages are named after concepts.

### 3.1 `core` module

| Package | Owns |
| --- | --- |
| `io.worxbend.nerdfonts.fonts` | `FamilyName` (validated opaque type — **the single path-traversal guard**; `parse(raw): Either[FamilyNameError, FamilyName]`, `value`, `Ordering`), `ReleaseTag` (opaque, non-blank trimmed), `ReleaseSelector` (`Latest \| Tagged(tag)`; `parse(raw)` maps blank/`latest` → `Latest`; `render`), `DestinationPath` (opaque, non-blank trimmed, unexpanded), `RefreshFontCache` and `DryRun` two-case enums, `InstallConfig(selector, destination, refreshFontCache, families: Vector[FamilyName])` with `InstallConfig.validated(...)` enforcing §4, `ConfigValidationError` ADT rendering the Go messages |
| `io.worxbend.nerdfonts.releases` | `Release(name: String, tag: ReleaseTag, families: Vector[String])`, `ReleaseCatalogue` port (`def releases(): Either[ReleaseError, Vector[Release]]`), `GitHubReleaseCatalogue` adapter (paginated GitHub API client; each page fetched through `HttpClient.get` with `SizeLimits.apiPage` = 8 MiB, decoded inside `consume`, wrapped in `timeoutEither(30.seconds)`), `ReleaseError` ADT (`NoReleases`, `NotFound(tag)`, `Http(HttpError)`, `Decode(message)`), `ReleaseSelection.select(releases, selector)`, `ReleaseUrls` (`download(selector, family): DownloadUrl`, `checksums(selector): Url`, Go `url.PathEscape` semantics), `Url`/`DownloadUrl` opaque types, `Sha256Digest` opaque type (lowercase 64-hex; `parse`, `fromBytes`), `ChecksumManifest.parse(text): Map[FamilyName, Sha256Digest]` |
| `io.worxbend.nerdfonts.http` | `HttpClient` port — one abstract member, loan-shaped so a body is never materialised: `def get[A](request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject)(consume: InputStream => A): Either[HttpError, A]`. The adapter owns the whole response lifecycle: non-2xx → `HttpError.Status(code)` (before `consume`); `Content-Length > limit` → `HttpError.TooLarge(limit)` (before `consume`); `consume` receives the body wrapped in `BoundedInputStream` (cap = `limit + 1`; Go's `LimitReader(max+1)` shape); after `consume` returns, a byte count above `limit` overrides the result with `Left(TooLarge(limit))` when `overflow == Reject`, while `Overflow.Truncate` makes the stream report EOF at `limit` and returns `consume`'s result (used only for the checksum manifest, §6.7). The body is closed on every path (`Using.resource`); `consume` is called at most once and must not retain the stream. `BoundedInputStream` lives here and is used by the JDK adapter and the test fake alike. `extension (c: HttpClient) def getString(request, limit, overflow): Either[HttpError, String]` (UTF-8). `HttpRequest(url: Url, headers: Map[String, String])`. `JdkHttpClient` adapter (`BodyHandlers.ofInputStream`, redirects `NORMAL`, 30 s **connect** timeout only — callers own overall deadlines with `timeoutEither` — default header `User-Agent: nerd-fonts-installer`; transport exceptions and `InterruptedException`-wrapping `IOException`s → `HttpError.Transport(cause)`). `HttpError` ADT (`Transport(cause: String)`, `Status(code: Int)`, `TooLarge(limit: ByteLimit)`); `HttpError.Status#statusLine` renders `"<code> <reason>"` via `HttpStatus.reasonPhrase(code): Option[String]` (built-in IANA table covering registered 1xx–5xx codes; fallback `"<code>"`) because `java.net.http` exposes no reason phrase while Go's `resp.Status` always carries one; **every** user-facing rendering of a non-2xx response goes through `statusLine`. `ByteLimit` opaque type over `Long` |
| `io.worxbend.nerdfonts.install` | `InstallRequest`, `InstallPlan` + `PlannedFamily` (pure output of `FontInstaller.plan`), `FontInstaller` (the engine), `ArchiveExtractor`, `DirectorySwap` (atomic replace with `.old` backup), `FontCacheRefresher` port + `FcCacheRefresher(processRunner)` adapter, `InstallEvent` (closed enum, eight cases, §6.9) + `InstallEventSink` (`def emit(event: InstallEvent): Unit`; contract: called from one thread at a time, in order — `FontInstaller` serialises through an Ox `Actor`, implementations must not add locking), `FamilyInstallError` ADT (`Download(url, HttpError)`, `Copy(url, tmp, cause)`, `ChecksumMismatch(actual, expected)`, `Staging(root, cause)`, `Extraction(zip, staging, ArchiveError)`, `Swap(SwapError)`, `TimedOut(limit)`), `InstallError` ADT (`Destination(root, cause)`, `Family(name: FamilyName, cause: FamilyInstallError)`, `FontCache(root, cause)`), `ArchiveError`, `SwapError`, `SizeLimits(download = 768 MiB, fontFile = 128 MiB, archive = 2 GiB, manifest = 1 MiB, apiPage = 8 MiB)` |
| `io.worxbend.nerdfonts.environment` | `Environment` port (`variable`, `homeDirectory: Option[os.Path]`, `workingDirectory: Either[EnvironmentError, os.Path]`) with `Environment.System` and `Environment.fixed`; `PathExpander.expand(path: DestinationPath, env): Either[PathError, os.Path]` (expands only a bare `~` and a leading `~/`; `~user`, an embedded `~` and everything else are returned unchanged; relative paths resolve against the working directory; an unresolvable home is `PathError.NoHome`); `ColourMode` enum (`Ansi \| Plain`) — the value every renderer consumes; detection lives in `cli` |
| `io.worxbend.nerdfonts.process` | `ProcessRunner` port (`run(spec: ProcessSpec): Either[ProcessError, ProcessResult]`, `lookPath(name: String): Option[os.Path]`), `ProcessSpec(command: Vector[String], stdin: Stdin = Stdin.Inherit, stdout: Stdout = Stdout.Inherit, stderr: Stderr = Stderr.Inherit)` with `enum Stdin { Inherit, FromFile(path) }`, `enum Stdout { Inherit, Capture }`, `enum Stderr { Inherit, Discard }` (`Inherit` = `ProcessBuilder.Redirect.INHERIT`, so `fc-cache` shares the real terminal), `ProcessResult(exit: ExitStatus, stdout: String)` (captured UTF-8 stdout, empty unless `Capture`), `ProcessError` ADT (`NotFound(command)`, `Failed(command, cause)`), `JdkProcessRunner` adapter (never a shell) |

### 3.2 `config` module — `io.worxbend.nerdfonts.config`

`ConfigLoader.load(path: os.Path): Either[ConfigError, InstallConfig]`, `ConfigDocument` (raw decoded shape,
`Option` fields, before defaults), `YamlConfigDecoder`, `JsonConfigDecoder` (both produce `ConfigDocument`
through one shared strict field decoder), `DiscoveredConfig(path: os.Path, config: InstallConfig)` (the Scala
counterpart of Go's `config.Source`), `ConfigLocations.candidates(env): Either[ConfigError, Vector[os.Path]]`
(the ordered, de-duplicated candidate list), `ConfigDiscovery.discover(env, load): Either[ConfigError, Option[DiscoveredConfig]]`
(first candidate that exists is loaded; `None` when none exists; an existing candidate that fails to load is
`Left` — never skipped), `ConfigError` ADT (`NotFound(path)`, `Unreadable(path, cause)`, `Parse(path, message)`,
`UnknownField(path, field)`, `WrongType(path, field, expected)`, `Invalid(path, ConfigValidationError)`,
`NoWorkingDirectory(cause)`). `ConfigError.render` yields the detail only; `Application` adds the
`load config <path>: ` / `load discovered config <path>: ` prefixes (§4). No origin marker is carried on
`InstallConfig`: the branch taken in `Application.resolveConfig` decides whether `Using config <path>` is printed.

### 3.3 `picker` module — `io.worxbend.nerdfonts.picker`

`PickerModel` (immutable state machine), `PickerStep` (`ChooseRelease`, `ChooseFamilies`, `Done`), `PickerKey`
(decoded key events: `Up, Down, Left, Right, PageUp, PageDown, Home, End, Tab, ShiftTab, Enter, Escape, Space,
Backspace, CtrlC, CtrlJ, CtrlK, Char(c)`), `ListState` (immutable filterable, scrollable list), `PickerView`
(renders a `Frame` = `Vector[String]` for a `Viewport(width, height)` and a `ColourMode`), `IconMode` enum +
`IconSet` (icon tables copied verbatim from the Go `icons.go`), `FamilyHint.of(family: String): String` (pure,
copied from Go `familyHint`: lower-case the name, first match wins — contains `mono` → `monospace favorite`,
`code` → `coding ligatures`, `symbol` → `glyph toolkit`, else `Nerd Font patched`), `Palette` (neon palette,
`brandRamp`, gradient helpers; every helper takes the `ColourMode` and returns unstyled text when `Plain`),
`Terminal` port (`withRawMode[A](body: RawTerminal => A): Either[TerminalError, A]` where `RawTerminal` has
`size(): Viewport`, `readKey(): Option[PickerKey]` (`None` on EOF), `write(frame: Frame): Unit`), `SttyTerminal`
adapter (§7), `KeyDecoder` (bytes → `PickerKey`, §7), `StdinSource` (the single process-wide
`abandonOnInterruptReads(System.in)`), `PickerSession.run(releases: Vector[Release], icons: IconMode,
colours: ColourMode, terminal: Terminal): Either[PickerError, PickerOutcome]` (drives the model ↔ terminal loop;
`releases.nonEmpty` is a precondition — `PickerError.NoReleases` otherwise), `PickerOutcome`
(`Selected(InstallConfig) \| Rejected(ConfigValidationError) \| Cancelled`),
`ReleaseLoadingSpinner.around[A](stderr: java.io.Writer, colours: ColourMode)(load: () => Either[ReleaseError, A]): Either[ReleaseError, A]`
(§7; lives here like Go's `tui.LoadReleases` but is invoked by `cli`, never by `PickerSession`).

### 3.4 `cli` module — `io.worxbend.nerdfonts.cli`

- `Cli` — the process boundary: builds the picocli root command, owns `-h/--help`, `--version`, picocli usage
  errors and `--icons` validation, turns parsed flags into an immutable `CliOptions`, calls `Application.run`,
  and returns `ExitCode.of(result)`. Signature
  `Cli.run(args: Array[String], out: PrintWriter, err: PrintWriter, deps: AppDependencies): Int`; the production
  overload builds `AppDependencies.production(...)`.
- `CliOptions(explicitConfig: Option[String], mode: CliMode, dryRun: DryRun, interactive: Interactive, icons: IconMode)`
  with `enum CliMode { FontNames, Install }` and `enum Interactive { Requested, NotRequested }`. The raw
  `--config` string is kept (Go treats `--config ""` as explicit and echoes the raw path).
- `AppDependencies` — function-typed seams (the Go `dependencies` struct): `loadConfig: os.Path => Either[ConfigError, InstallConfig]`,
  `discoverConfig: () => Either[ConfigError, Option[DiscoveredConfig]]`, `configCandidates: () => Vector[os.Path]`,
  `listReleases: () => Either[ReleaseError, Vector[Release]]`, `runPicker: (Vector[Release], IconMode, ColourMode) => Either[PickerError, PickerOutcome]`,
  `installFonts: (InstallRequest, InstallEventSink) => Either[InstallError, Unit]`, `isTerminal: () => Boolean`,
  `expandDestination: DestinationPath => Either[PathError, os.Path]`. Production binds `runPicker` to
  `PickerSession.run(_, _, _, SttyTerminal(processRunner))`; tests substitute pure functions so no `Terminal`
  crosses the seam.
- `Application.run(options: CliOptions, deps: AppDependencies, out: PrintWriter, err: PrintWriter): Either[AppFailure, AppOutcome]`
  composed of `resolveConfig`, `printFontNames`, `selectRelease`, `install`; never sees `args` or picocli.
- `enum AppOutcome { Installed, DryRunPrinted, FontNamesPrinted, PickerCancelled }`.
- `AppFailure` — failure ADT: `Config(ConfigError, prefixPath)`, `DiscoveredConfig(ConfigError)`, `NoConfig(hint)`,
  `NotATerminal`, `Release(ReleaseError)`, `Picker(PickerError)`, `UnsafeSelection(ConfigValidationError)`,
  `Destination(PathError)`, `Install(InstallError)`, `Interrupted(phase)`. Every case renders one stderr line.
  Cancellation is **not** a failure: it is `Right(AppOutcome.PickerCancelled)`.
- `ExitCode.of(result: Either[AppFailure, AppOutcome]): Int` — the only place application results become POSIX
  codes: any `Right` → 0; `NoConfig`, `NotATerminal`, `Release(NotFound | NoReleases)` → 2; every other `Left`
  → 1. picocli's own `USAGE` (2) for malformed flags and 0 for `--help`/`--version` are produced inside
  `CommandLine.execute` and are the sole codes not routed through it.
- `OutputStyle.detect(env, consoleAttached): ColourMode` (`NO_COLOR`, `TERM=dumb`, `CLICOLOR_FORCE`/`FORCE_COLOR`
  escape hatches — same rules as binstaller's `CliOutputStyle`).
- `ConsoleEventRenderer(out, err, colours)` — the only place `InstallEvent`s become text; owns the Go-parity
  wording and the stdout/stderr routing (§6.9); exhaustive `match` with no wildcard; a plain writer with no
  locking, relying on the `InstallEventSink` contract.
- `TerminalProbe.isTerminal(): Boolean` — `Option(System.console()).exists(_.isTerminal)`. On the JDK 25 toolchain
  the default console provider (`java.base`) returns a console only when both stdin and stdout are TTYs — the same
  condition Go checks with `ModeCharDevice` on both streams — and `isTerminal` double-checks; no subprocess.
- `BuildInfo` (generated by Mill: `version`, `commit`, `buildDate`).

### 3.5 `app` module — `io.worxbend.nerdfonts.app`

`Main` only: JVM/native-image entry point plus SIGINT handler registration (§6.8). Plus
`app/resources/META-INF/native-image/io.worxbend/nerd-fonts-installer/reflect-config.json` and a test asserting
every `@Command`-annotated class in `cli` is listed there.

## 4. Configuration

Format is chosen by the file's last extension only: a path whose extension equals `.json` case-insensitively
(`x.JSON` is JSON, `x.json.bak` is not) is decoded as JSON; every other path — `.yaml`, `.yml`, `.conf`, any
other extension, or none — is decoded as YAML. Both decoders are strict: unknown keys are errors.

| Key | Required | Default | Notes |
| --- | :-: | --- | --- |
| `release` | no | `latest` | `latest` or a tag such as `v3.4.0`; trimmed; blank after trim is an error |
| `destination` | no | `~/.local/share/fonts/NerdFonts` | trimmed; blank after trim is an error; `~` expanded by `Application` via `PathExpander` before the install request is built (§6.1) |
| `refresh_font_cache` | no | `false` | boolean |
| `families` | yes | — | list of strings; each trimmed then validated by `FamilyName`; duplicates are an error |

JSON: a single top-level object; trailing content after the first value is an error whose message contains
`multiple json values`. JSON does not coerce: a number/bool/array/object in a string position is `WrongType`;
`null` for `release`/`destination` leaves the default; `null` inside `families` is the empty string →
`font family names cannot be empty`.

YAML scalar handling (mirrors how Go's yaml.v3 decodes into typed fields; apply to the scala-yaml AST regardless
of the node's resolved tag):

- `release`, `destination`, each `families` entry: any non-null scalar — plain, quoted, int, float, bool — is
  taken as its literal text (`families: [3270]` → `"3270"`, a real Nerd Font family; `release: 1.0` → `"1.0"`).
- A null scalar (`~`, `null`, empty value) for `release`/`destination` leaves the field unset so the default
  applies. A null entry inside `families` is dropped (`[~, Hack]` → `["Hack"]`); `families: ~` is an empty list
  (then `at least one font family is required`).
- `refresh_font_cache`: any scalar whose text, case-insensitively, is one of `true/false/yes/no/on/off/y/n`
  is accepted as the corresponding boolean, quoted or not (deliberate, slightly more lenient than yaml.v3, which
  rejects quoted `"true"`); anything else, including `1`/`0`, is `WrongType(path, "refresh_font_cache", "boolean")`.
  Null is `false`.
- A sequence/mapping where a scalar is expected, or a scalar/mapping where the `families` sequence is expected,
  is `WrongType(path, field, expected)`. An empty document is a document with everything absent.

Resolution order (highest first), implemented in `Application` + `ConfigLocations`:

1. `--config <path>` — explicit; load/parse/validation errors are fatal with exit 1 and the message
   `load config <path>: <cause>`.
2. `$NERD_FONTS_INSTALLER_CONFIG` (trimmed; blank falls through) — treated exactly like `--config` (same prefix,
   exit 1).
3. `./nerd-fonts-installer.{yaml,yml,json,conf}`
4. `./nerd-fonts-installer/config.{yaml,yml,json,conf}`
5. The same two shapes under `$XDG_CONFIG_HOME` when it is an absolute path, else under `~/.config`.

The candidate list is built in that order and de-duplicated preserving first occurrence (when cwd equals the
config home there are 8 candidates, not 16, in both discovery and the no-config hint). If the home directory
cannot be resolved (and `$XDG_CONFIG_HOME` is not absolute) the config-home candidates are silently omitted. If
the working directory cannot be determined, discovery fails with `locate current directory: <cause>` — exit 1
(also on the `--font-names` path) and no picker is started.

Discovery (`ConfigDiscovery.discover`) returns `Some(DiscoveredConfig(path, config))` for the first candidate
that exists, `None` if none exists, and a `ConfigError` (fatal: `load discovered config <path>: <cause>`, exit 1)
if an existing candidate fails to load — only a not-exists error skips to the next candidate.
`Application.resolveConfig` prints `Using config <path>` to stderr **only** in the discovered branch; explicit and
env-var configs print nothing. `printFontNames` also falls back to a discovered config for `release` but never
prints `Using config`.

`FamilyName.parse(raw)` rejects: empty, `.`, `..`, any `/` or `\`, NUL, absolute paths, and anything whose base
name differs from itself. Messages: `font family names cannot be empty`, `unsafe font family name "<name>"`.
Config validation messages: `release is required`, `destination is required`,
`at least one font family is required`, `duplicate font family "<name>"`.

## 5. Release catalogue

`GitHubReleaseCatalogue` GETs `https://api.github.com/repos/ryanoasis/nerd-fonts/releases?per_page=100&page=N`
for N = 1..maxPages (default 5), headers `Accept: application/vnd.github+json`, `User-Agent: nerd-fonts-installer`.
Each page request has a **30 s overall deadline** covering connect, headers, body and decode
(`timeoutEither(30.seconds, ...)` around the whole page fetch — Go's `http.Client{Timeout: 30s}`); a timeout
surfaces as `ReleaseError.Http(HttpError.Transport("timed out after 30 seconds"))`. Stop when the **raw** page is
empty (not when filtering emptied it). Drop drafts and blank tags. Families = sorted unique asset names ending in
`.zip` (case-insensitive) minus the extension; drop releases with no families. Release `name` falls back to the tag.

`Release.families` is `Vector[String]` — raw asset stems, deliberately **not** `FamilyName`: they are untrusted
upstream data used only for display and selection, `--font-names` prints them verbatim (Go prints
`selected.Families` unmodified), and the catalogue never drops or rejects a stem. The conversion to `FamilyName`
happens exactly once, at the picker → `InstallConfig` boundary (§7).

Errors: empty result → `ReleaseError.NoReleases` (`no Nerd Fonts releases found`); unknown tag →
`ReleaseError.NotFound(tag)` (`nerd fonts release "<tag>" was not found`); non-2xx page →
`list Nerd Fonts releases: <statusLine>` (e.g. `list Nerd Fonts releases: 403 Forbidden`); transport failure →
`list Nerd Fonts releases: <cause>`; undecodable body → `decode Nerd Fonts releases: <cause>`.

`ReleaseSelection.select(releases, selector)`: `Latest` → first element; `Tagged(t)` → first with matching tag.

URL builders (path segments percent-escaped like Go's `url.PathEscape`; test against the Go expectations in
`internal/fonts/installer_test.go` `TestReleaseURL` / `TestChecksumURLEscapesRelease`):

- latest: `https://github.com/ryanoasis/nerd-fonts/releases/latest/download/<Family>.zip`
- tagged: `https://github.com/ryanoasis/nerd-fonts/releases/download/<tag>/<Family>.zip`
- checksums: the same two shapes with `SHA-256.txt`

`ChecksumManifest.parse(text)`: each line `<hex>  <file>`; keep only `.zip` entries; digest lowercased; key is
the file name without extension parsed as `FamilyName` (lines that fail to parse are skipped — they can never
match a validated family). Read at most 1 MiB, **truncating** (not rejecting) anything beyond, like Go's
`io.LimitReader`; a line cut by the limit is skipped like any other unparseable line. Manifest fetch failure is a
**warning**, digest mismatch is **fatal** — never weaken that.

## 6. Install engine (`FontInstaller`)

### 6.1 Inputs and plan

`InstallRequest(selector: ReleaseSelector, root: os.Path, families: Vector[FamilyName], refreshFontCache: RefreshFontCache, dryRun: DryRun)`.
`root` is already absolute: `Application` computes it as `PathExpander.expand(config.destination)` before
building the request (`DestinationPath` never crosses into `install`); expansion failure is
`AppFailure.Destination` → exit 1. `FontInstaller` depends on `HttpClient`, `FontCacheRefresher`,
`SizeLimits`, a temp-directory `os.Path` and the caller's `InstallEventSink` only — never on `Environment`.

`FontInstaller.plan(request): InstallPlan` is pure: it de-duplicates families preserving order (defensive; config
already rejects duplicates) and computes per family `PlannedFamily(name, url = ReleaseUrls.download(selector, name), targetDir = root / name.value)`.
`install(request, sink)` calls `plan` first and then either renders it (dry run) or executes it, so the executed
url/target are the values a dry run printed.

### 6.2 Dry run

For each `plan.families` emit `InstallEvent.WouldInstall(family, url, targetDir)`, then `WouldRefreshCache(root)`
when enabled. Nothing touches the network or the disk. `ConsoleEventRenderer` writes these two cases to **stdout**:

```
• Would install <Family> from <url> into <root>/<Family>
↻ Would refresh font cache for <root>
```

Every progress/warning line the renderer emits carries the Go glyph prefix (`•` plan/warnings, `↻` dry-run cache,
`⠋` in progress, `✅` done). The glyph is part of the wording and is kept in `Plain` mode; only colour is dropped.

### 6.3 Real run

0. `os.makeDir.all(root)`; failure is `InstallError.Destination(root, cause)` → `create destination <root>: <cause>`
   and nothing else runs (no HTTP call is made). Then fetch the checksum manifest once (§6.7).

Per family (`installFamily(planned): Either[FamilyInstallError, Unit]`):

1. Emit `Started(family, url)` → `⠋ Installing Nerd Font <Family> from <url>`.
2. `httpClient.get(url, limits.download)` (768 MiB) with a `consume` that copies the stream to a temp file
   `nerd-font-*.zip` under the temp directory through a SHA-256 `DigestInputStream`. Both size checks are the
   port's responsibility; the installer maps `HttpError` → `FamilyInstallError.Download(url, error)` and a
   copy/IO failure → `Copy(url, tmp, cause)`.
3. If a digest is known for the family and differs: `FamilyInstallError.ChecksumMismatch(actual, expected)`.
4. Create a unique staging dir `<root>/.<Family>-<random>` (`Staging(root, cause)` on failure); extract only
   entries whose extension is `.ttf`, `.otf` or `.ttc` compared case-insensitively (`.TTF` is a font file),
   flattened to the base name, skipping directories, enforcing `limits.fontFile` (128 MiB) per entry (declared
   size first, then the actual stream via cap + 1) and `limits.archive` (2 GiB) total. Zero font files →
   `ArchiveError.NoFontFiles`. Written files are `fsync`ed and closed explicitly; a close error fails the install.
5. `DirectorySwap.replace(staging, target)`: remove stale `<target>.old`, rename existing target to `.old`, rename
   staging into place (roll back on failure), then best-effort delete `.old` — a cleanup failure after the swap
   is **never** reported as an install failure.
6. Emit `Installed(family, target)` → `✅ Installed <Family> into <target>`.
7. The temp zip and staging dir are always removed (`finally`), including on interruption (§6.8).

Each family has a 10-minute deadline: `installFamily` wraps its whole body (steps 1–7, including the `finally`)
in `timeoutEither(10.minutes, FamilyInstallError.TimedOut(10.minutes))(...)`. Never the throwing `ox.timeout`:
its `TimeoutException` would escape §6.5 as a defect. On overrun Ox interrupts the body and waits for it, so the
cleanup has run when the `Left` is observed; the `Left` then travels the ordinary path and cancels siblings.

Message shapes (`<cause>` is the nested error's text; platform wording, not a parity target):

| Error | Rendered as |
| --- | --- |
| `InstallError.Family(family, e)` | `install Nerd Font family <Family>: <render(e)>` — the family name MUST appear |
| `InstallError.Destination(root, cause)` | `create destination <root>: <cause>` |
| `Download(url, Status(code))` | `download <url>: <statusLine>` (e.g. `download https://…/Hack.zip: 404 Not Found`) |
| `Download(url, Transport(cause))` | `download <url>: <cause>` |
| `Download(url, TooLarge(limit))` | `download <url>: exceeds <limit> byte limit` (the adapter's Content-Length variant renders `download <url>: size <n> bytes exceeds <limit> byte limit` when `n` is known) |
| `Copy(url, tmp, cause)` | `copy download <url> to <tmp>: <cause>` |
| `ChecksumMismatch(got, want)` | `checksum mismatch for <Family>: downloaded sha256 <got>, expected <want>` (name supplied by the `Family` wrapper) |
| `Staging(root, cause)` | `create temporary family destination in <root>: <cause>` |
| `Extraction(zip, staging, e)` | `extract <zip> to <staging>: <render(e)>` where `e` renders `open font zip <zip>: <cause>` / `extract <zip>: no font files found` / `extract <zip>: font file <entry> declares <n> bytes, exceeds <limit> byte limit` / `extract <zip>: total uncompressed size exceeds <limit> byte limit` / `extract <entry>: <cause>` |
| `Swap(e)` | `remove old backup <backup>: <cause>` / `move existing destination <target> to <backup>: <cause>` / `move extracted fonts <staging> to <target>: <cause>` |
| `TimedOut(limit)` | `timed out after 10 minutes` |
| `InstallError.FontCache(root, cause)` | `run fc-cache for <root>: <cause>` |

`Application` prefixes the rendered `InstallError` with `install fonts: `. Because the fan-out stops at the first
failure, the output is exactly one line, e.g.
`install fonts: install Nerd Font family Inter: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Inter.zip: 404 Not Found`.

### 6.4 Concurrency

Inside one `supervised` scope: `val events = Actor.create(sink)` wraps the caller-supplied sink once, then
`Flow.fromIterable(plan.families).mapParUnordered(min(4, n))(installFamily).runDrain()`. Paths per family are
disjoint (`<root>/<Family>`, its own staging dir, its own `.old`), which is what makes this safe. Forks emit via
`events.ask(_.emit(event))` — `ask`, not `tell`, so each fork blocks until its line is written: per-family order
is preserved, nothing is buffered at scope teardown, and a sink failure propagates to the emitting fork. Events
outside the fan-out (checksum warning, font-cache lines) go straight to `sink.emit` on the calling thread.
Consequently `InstallEventSink.emit` is always invoked from one thread at a time, in submission order.

### 6.5 Error propagation

A failing family must cancel in-flight siblings (Go `errgroup` semantics). Inside the flow, a `Left(cause)` from
`installFamily(family)` is raised as the private `FamilyInstallAborted(family, cause)` exception;
`FontInstaller.install` catches exactly that type at its boundary (`.catching[FamilyInstallAborted]`) and returns
`Left(InstallError.Family(family, cause))`. This is the one sanctioned use of an exception for a recoverable
error and it never escapes the module; no other exception type may cross this boundary (a deadline is already a
`Left`, §6.3). Already-completed families stay installed.

### 6.6 Font cache

When `refreshFontCache` is on and not dry-run: if `fc-cache` is not on `PATH`, emit `FontCacheUnavailable` and
succeed. Otherwise emit `RefreshingFontCache(root)`, run `ProcessSpec(Vector("fc-cache", "-f", root))` (all
streams `Inherit`), then emit `FontCacheRefreshed`. A non-zero exit or launch failure is
`InstallError.FontCache(root, cause)` → exit 1.

### 6.7 Checksum fetch

Fetched once before the fan-out via `httpClient.getString(checksumUrl, limits.manifest, Overflow.Truncate)`
under a 30 s `timeoutEither` (stricter than Go, acceptable because failure is only a warning). Any `HttpError`
or timeout → emit `ChecksumManifestUnavailable(cause)` and continue with an empty map, where `<cause>` is
`statusLine` for a non-2xx response and the transport text otherwise. A truncated body still yields every
complete line parsed before the cut. Any family absent from the map installs unverified; a present-but-mismatching
digest is fatal.

### 6.8 Interrupts (SIGINT parity)

Go cancels its root context on SIGINT: in-flight downloads abort, every `defer` runs, and the process exits 1 with
`install fonts: install Nerd Font family <F>: …: context canceled`. The JVM's default handler exits 130 without
unwinding `finally` (and native-image is killed outright), which would leak `nerd-font-*.zip` and
`<root>/.<Family>-*` staging dirs. Therefore:

- `Main` registers `sun.misc.Signal.handle(new Signal("INT"), _ => mainThread.interrupt())` before calling
  `Cli.run`; a second SIGINT while the first is unwinding calls `Runtime.getRuntime.halt(130)` (escape hatch; the
  only intentional deviation from Go, which absorbs repeats). `sun.misc.Signal` works under native-image without
  extra flags.
- Interrupting the main thread ends the Ox scope: forks are interrupted, body reads throw, each `installFamily`
  `finally` removes its temp zip and staging dir, and an existing `<root>/<Family>` is untouched.
- `InterruptedException` is never swallowed inside `core`/`picker`; `Cli.run` catches it exactly once after
  `Application.run` returns abruptly and maps it to `AppFailure.Interrupted(phase)`: rendered
  `install fonts: interrupted` during install, otherwise `interrupted`; exit **1**.
- Picker/spinner: `stty raw` clears ISIG, so keyboard Ctrl-C reaches the picker as byte `0x03` → `Cancelled` →
  exit 0. An external `kill -INT` interrupts the main thread; `abandonOnInterruptReads` unblocks the stdin read
  and `SttyTerminal`'s `finally` leaves the alternate screen, re-shows the cursor and restores the saved `stty -g`
  settings before the exit-1 path.

### 6.9 Events

Everything the engine says to the user is an `InstallEvent`; `FontInstaller` never writes to a stream and no
`String` message crosses `InstallEventSink`. The enum is closed:

```scala
enum InstallEvent:
  case WouldInstall(family: FamilyName, url: DownloadUrl, target: os.Path)   // §6.2
  case WouldRefreshCache(root: os.Path)                                       // §6.2
  case Started(family: FamilyName, url: DownloadUrl)                          // §6.3 step 1
  case Installed(family: FamilyName, target: os.Path)                         // §6.3 step 6
  case ChecksumManifestUnavailable(cause: String)                             // §6.7
  case FontCacheUnavailable                                                   // §6.6
  case RefreshingFontCache(root: os.Path)                                     // §6.6
  case FontCacheRefreshed                                                     // §6.6
```

| Event | Stream | Line |
| --- | --- | --- |
| `WouldInstall` | stdout | `• Would install <Family> from <url> into <target>` |
| `WouldRefreshCache` | stdout | `↻ Would refresh font cache for <root>` |
| `Started` | stderr | `⠋ Installing Nerd Font <Family> from <url>` |
| `Installed` | stderr | `✅ Installed <Family> into <target>` |
| `ChecksumManifestUnavailable` | stderr | `• Checksum manifest unavailable (<cause>); installing without integrity verification.` |
| `FontCacheUnavailable` | stderr | `• fc-cache is not available; skipping font cache refresh.` |
| `RefreshingFontCache` | stderr | `⠋ Refreshing font cache for <root>` |
| `FontCacheRefreshed` | stderr | `✅ Font cache refreshed` |

Colours (ANSI mode only; Go lipgloss numbers): spinner glyph 63, success glyph 42 bold, warning glyph 214, family
name 81 bold, url 39 underlined, path 219.

## 7. Interactive picker

Entered only from `Application.resolveConfig` when: no explicit/env/discovered config, `--interactive` given, and
`TerminalProbe` says both stdin and stdout are terminals. Otherwise: no config + not interactive → exit 2 with
`no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: <candidates joined by ", ">`
(when the candidate list cannot be computed or is empty, the hint degrades to
`no config found; pass --config or set NERD_FONTS_INSTALLER_CONFIG`); interactive but not a terminal → exit 2
`no config found; --interactive requires stdin and stdout terminals`.

The interactive branch: print `No config found. Starting interactive mode...` to stderr; run
`ReleaseLoadingSpinner.around(stderr, colours)(listReleases)`; a `Left(ReleaseError)` returns as
`AppFailure.Release(err)` so `NoReleases` → 2 and `Http`/`Decode` → 1 exactly as for `--font-names`; on
`Right(releases)` call `runPicker(releases, icons, colours)`; `Cancelled` → `Right(PickerCancelled)` → exit 0;
`Rejected(e)` → `AppFailure.UnsafeSelection(e)` rendered `install fonts: <e>` → exit 1; `Selected(config)`
continues to install. The picker never performs network IO.

The spinner block mirrors Go's `tui.LoadReleases` on stderr:

```

  ✦ nerd-fonts-installer
  ⠋ Loading Nerd Fonts releases
```

(leading blank line, two-space indents; the brand line is gradient-coloured in `Ansi` mode; the spinner cycles
bubbles' `MiniDot` frames `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` by rewriting the second line with `\r`). On success the second line ends
as `  ✓ Releases loaded`; on failure it ends with the error message; an interrupt during the load ends it with
`  interrupted` before the `InterruptedException` propagates, so the line is never left mid-spin.

Model (pure, fully unit-tested without a terminal):

- Steps: `ChooseRelease` → `ChooseFamilies` → `Done`.
- Keys are resolved in strict precedence, **independent of filter state** (the Go model consumes its own keys
  before the list sees them):
  1. Global, both steps: `q` and `Ctrl-C` cancel; `Esc` goes back to releases on the families step and cancels on
     the release step. These fire even while the filter input is focused — `Esc` never clears the filter and `q`
     cannot be typed into it.
  2. Step keys, consumed before the list: release step — `Enter` chooses the highlighted (filtered) release;
     families step — `Enter` finishes (no-op when nothing is selected, even mid-filter), `Space` toggles the
     highlighted family, `a` selects all / clears all, `b` goes back. None of these can be typed into the family
     filter.
  3. Everything else goes to the list. Browsing: `Up`/`k`, `Down`/`j`; `PgUp`/`Left` and `PgDn`/`Right` page
     (also `b` on the release step, where it is not already claimed as a step key); `Home`/`g` first; `End`/`G`
     last; `/` focuses the filter input (cursor reset to first item). Filtering: printable characters and
     Backspace edit the filter and re-filter live; `Up`/`Down`/`Tab`/`Shift-Tab`/`Ctrl-K`/`Ctrl-J` apply the
     filter when the input is non-empty (if it filters to nothing the filter is cleared instead); paging keys are
     disabled while the input is focused. `Enter` never applies the filter. An applied filter persists until it is
     re-opened with `/` and emptied. (Deviation: Go's `h/l/f/d/u` paging aliases are not bound.)
  - Matching is fuzzy: case-insensitive subsequence match over `title + " " + description + " " + value`, ranked by
    first-match position then match span, stable for ties (approximates sahilm/fuzzy).
- Result: `Cancelled` when cancelled **or finished with nothing selected**; otherwise every selected stem is run
  through `FamilyName.parse`. All parse → `Selected(InstallConfig(tag, destination "~/.local/share/fonts/NerdFonts", refreshFontCache = on, families sorted))`;
  any failure → `Rejected(ConfigValidationError)` carrying the first failure (renders as Go's
  `unsafe font family name "<name>"`). This is the only place picker output crosses the `FamilyName` boundary.
- Layout budget mirrors the Go tool: banner box (gradient wordmark, breadcrumb on wide layouts, step title,
  subtitle + badges dropped when compact = height < 26, gradient rule), list panel, optional side panel (width ≥
  104 and only if it fits), footer help; constants `chromeHeight = 16`, `compactChromeHeight = 14`,
  `minListHeight = 9`, floors 48 columns / 24 rows, `bodyWidth` cap 132, `previewWidth` 34. The rendered frame
  **never exceeds `safeHeight`** and no line is visibly wider than `safeWidth` — test across the Go size matrix
  (40×10, 60×20, 80×24, 104×25, 100×30, 112×34, 160×50, 200×60) for both steps.
- Icon sets and the Nerd-family glyph table are copied verbatim from the Go `icons.go`. `auto` == `unicode`.
- Rendering is a full-frame redraw per key on the alternate screen. Colours go through `fansi` true-colour and are
  emitted only when the `ColourMode` passed to `PickerView.render` is `Ansi`; `cli` derives that value from
  `OutputStyle` and hands it in via `runPicker`, so `picker` never references `cli`.

`SttyTerminal` issues every `stty` call through `ProcessRunner` as
`ProcessSpec(Vector("stty", …), stdin = Stdin.FromFile(os.Path("/dev/tty")), stdout = Stdout.Capture)` — no shell.
`stty -g` saves the settings string (opaque; Linux and macOS formats differ; only ever passed back to
`stty <saved>`), `stty raw -echo` enters raw mode, and the saved string is restored in a `finally` that wraps
the raw-mode entry itself, not just the session body — so `stty <saved>` still runs even if `stty raw -echo`
throws (an `InterruptedException` can surface from `Process.waitFor()` after the child has already applied the
termios change) rather than returning a normal `Left`. The alternate screen and cursor sequences are emitted
only once raw mode is confirmed entered. Size comes from `stty size` (`rows cols`) on every frame; fallback
80×24 on any `Left`, non-zero exit or parse failure.

**Output.** `stty raw` clears `opost`/`onlcr`, so the terminal no longer turns `\n` into CR+LF. `write(frame)`
emits `ESC[?1049h` once on entry (alternate screen) and `ESC[?25l` (hide cursor); each frame is `ESC[H`, the lines
joined with `\r\n` (each followed by `ESC[K`), then `ESC[J`, as one flushed write; exit emits `ESC[?25h` and
`ESC[?1049l`. Never write a bare `\n` while in raw mode (a test asserts no `\n` without a preceding `\r`).

**Input.** Exactly one `abandonOnInterruptReads(System.in)` per process (`StdinSource`); `SttyTerminal` never
wraps `System.in` again. Neither `PickerSession` nor `KeyDecoder` opens a `supervised` scope of its own —
`PickerSession.drive` is a plain tail-recursive loop. `abandonOnInterruptReads` and `timeoutOption` (the
post-`ESC` byte race) are each self-contained Ox calls that manage their own short-lived internal fork per
invocation, reading on a detached thread and racing it against interruption of the calling thread rather than
requiring one. That is what makes cancellation safe without a scope here: `app.Main` turns SIGINT into
`mainThread.interrupt()`, which unblocks a pending `read()` promptly instead of deadlocking on it (the
underlying blocked OS read is abandoned, not force-cancelled), and `SttyTerminal.withRawMode`'s `finally`
still restores the saved terminal settings when the resulting `InterruptedException` unwinds through it.
`KeyDecoder`: `0x03` = Ctrl-C, `0x0a` = Ctrl-J, `0x0b` = Ctrl-K, `0x0d` = Enter, `0x09` = Tab, `0x7f`/`0x08` =
Backspace, `0x20` = Space, `0x1b` starts an escape sequence, other bytes decode as UTF-8 `Char`. After `0x1b` the
next byte is read with `timeoutOption(escapeTimeout)` (default 50 ms; constructor parameter); timeout → `Escape`.
`[` (CSI) or `O` (SS3) → read the final byte: `A/B/C/D` → `Up/Down/Right/Left`, `H/F` → `Home/End`, `Z` →
`ShiftTab`, digits followed by `~` (`5~`/`6~`/`1~`/`4~`) → `PageUp/PageDown/Home/End`; anything else is ignored.

## 8. CLI

Single root command `nerd-fonts-installer`, no subcommands, `sortOptions = false`, custom header
(`Nerd Fonts, installed the boring way.`). `mixinStandardHelpOptions` is **off**: the tool owns `--version`
(picocli's mixin clashes with an option of the same name — verified: the clash silently drops the mixin and
`--help` stops working). Declare `-h, -help, --help` with `usageHelp = true` and `-version, --version` with
`versionHelp = true` backed by an `IVersionProvider` reading `BuildInfo`; no `-V` alias (parity with Go).

Flag-parsing parity with Go's `flag` package:

- Declare every option with both spellings: `names = Array("-config", "--config")`, `Array("-dry-run", "--dry-run")`,
  `Array("-font-names", "--font-names")`, `Array("-interactive", "--interactive")`, `Array("-icons", "--icons")`.
- Booleans stay plain arity-0 options; picocli already accepts `--dry-run=false`. Do **not** use `arity = "0..1"`.
- Positional arguments are accepted and ignored, and parsing stops at the first positional (Go stops there):
  a hidden `@Parameters(arity = "0..*", hidden = true)` sink plus `setStopAtPositional(true)`. Do **not** use
  `setUnmatchedArgumentsAllowed(true)`: unknown options such as `--bogus` must still exit 2.
- `--icons` is bound as a raw `String`, normalised with trim + lower-case (`' NERD '` is valid) and validated
  **before** `--version` is honoured (`--version --icons bogus` exits 2) with the byte-exact message quoting the raw
  value: `invalid --icons "<raw>"; use auto, nerd, unicode, or ascii`. Implemented in `Cli`, not as a converter.

`--version` prints `nerd-fonts-installer <version> (<commit>, <date>)` (Go: `"%s %s (%s, %s)\n"`). All three are
compile-time constants in the generated `cli.BuildInfo` (`version`, `commit`, `buildDate`); `build.mill` derives
commit and date from `Task.Input` tasks (`git rev-parse --short=12 HEAD` or `unknown`; `Task.env`
`NERD_FONTS_INSTALLER_BUILD_DATE` or `unknown`). `release.yml` exports the date variable before invoking Mill.

In `Cli.run`: 1. `CommandLine.execute(args)`: picocli usage errors → 2; `--help`/`--version` → print, 0.
2. Validate `--icons` → 2 on failure. 3. Build `CliOptions`. 4. `ExitCode.of(Application.run(options, deps, out, err))`,
printing the `AppFailure` message on `Left` (prefixed `install fonts: ` for install failures and unsafe picker
selections). 5. `InterruptedException` escaping `Application.run` → `AppFailure.Interrupted` → 1 (§6.8).

In `Application.run(options)`: 1. `CliMode.FontNames` → resolve release from explicit/env/discovered config
(default `latest`), list, select, print → `Right(FontNamesPrinted)`; errors: `NotFound`/`NoReleases` → 2,
config load errors and everything else → 1. 2. Resolve config (explicit → env → discovered → picker);
`Cancelled` → `Right(PickerCancelled)`. 3. Expand the destination, build the `InstallRequest`, install (or dry
run) → `Right(Installed)` / `Right(DryRunPrinted)`.

All informational progress goes to **stderr**; stdout carries only machine-readable output (`--font-names`,
dry-run plan lines, `fc-cache` output). Error lines are the bare rendered message. Implementations use
`PrintWriter`s created with `autoFlush = true`.

## 9. Testing strategy

- `core/fonts`: property test — anything `FamilyName` accepts is non-empty, contains no `/`, `\` or NUL, is not
  `.`/`..`, is not absolute, equals its own base name; the exact Go acceptance/rejection tables.
- `core/http`: status ≥ 300 rejected before `consume`; `Content-Length > limit` rejected before `consume`; body of
  `limit + 1` bytes with no `Content-Length` → `TooLarge` even when `consume` returned normally;
  `Overflow.Truncate` returns the first `limit` bytes; the stream is closed when `consume` returns or throws;
  `statusLine` for 404/403/429 and an unregistered code (`599` → `"599"`). The in-memory fake serves
  `Map[Url, Response]` through the shared `BoundedInputStream` so the cap logic is real.
- `core/releases`: pagination, filtered page continues, max pages, non-2xx, transport, decode, no releases when
  all filtered, families sorted/unique/zip-only, URL escaping tables, `ChecksumManifest` parsing (two-column
  format, lowercase, non-zip lines ignored, unparseable stems skipped, truncated last line skipped).
- `core/install`: dry run touches nothing (fake HTTP saw zero requests, no dirs created); destination that cannot
  be created fails with `create destination` before any HTTP call; single install layout; `.TTF` extracted;
  multi-family concurrent install with n ≥ 4 asserting every `Installed` line is present and intact and each
  family's `Started` precedes its `Installed`, observed through a plain unsynchronised list sink; one failing
  family fails the run and leaves already-finished families installed; existing family dir kept on extraction
  failure; checksum mismatch fatal with the doubled-name message; missing manifest warns and proceeds; truncated
  manifest keeps parsed digests; oversize download via Content-Length and via stream; oversize entry (declared
  and actual); oversize total; duplicate families collapse; rollback when the final rename fails; fc-cache missing
  warns; fc-cache non-zero fails; interrupt during download (fake `HttpClient` blocking on a latch until the test
  interrupts the installing thread) removes the temp zip and staging dir, leaves the pre-existing family dir
  intact, and does not deadlock; per-family deadline (tiny limit in test) yields `TimedOut` with cleanup done.
- `config`: defaults, trimming, unknown field, wrong type, JSON, blank-after-trim per field, duplicates, YAML
  scalar coercion (`families: [3270]`, `refresh_font_cache: yes` / `"yes"` / `on`, `1` → wrong type,
  `[~, Hack]` → `["Hack"]`, `release: ~` → default; JSON `"families": [3270]` → wrong type), `.conf`/`.yml`/no
  extension parsed as YAML, `.JSON` as JSON, discovery order, de-duplication when cwd = config home, XDG rules
  (absolute vs relative), missing home omits config-home candidates, existing-but-broken candidate is fatal.
- `picker`: model transitions for every rule in the precedence table (including `q` while filtering cancels; `Esc`
  while filtering goes back / cancels and leaves the filter text; `Enter` while filtering with 0 selected is a
  no-op; `Enter` while filtering on the release step chooses the first fuzzy match; `Space`/`a`/`b` while
  filtering are not inserted; `Up`/`Down` with a non-empty filter apply it), result mapping (sorted, none →
  `Cancelled`, `../x` selected → `Rejected`), `FamilyHint`, icon tables, `ListState` filtering/scrolling, the
  frame height/width budget matrix, a `Plain` frame contains no `ESC[` colour sequences, `KeyDecoder` table (bare
  ESC, `ESC [ A`, `ESC O A`, `ESC [ 5 ~`, multi-byte UTF-8, Ctrl-C), `write` emits `\r\n`-joined lines only, and
  a `ScriptedTerminal` end-to-end session (choose release, toggle two families, enter → `Selected`).
- `cli`: golden tests through `Cli.run` with fake dependencies for every exit-code path: `--version` format,
  `--font-names` (latest, configured release, env override, discovered config, missing release → 2, no releases →
  2, broken config → 1), malformed flag → 2, `--bogus` → 2, single-dash aliases, `--dry-run=false`, positionals
  ignored and `extra --dry-run` leaves dry-run off, invalid `--icons` → 2 even with `--version`, `--icons ' NERD '`
  accepted, no config non-interactive → 2 with the hint, `--interactive` without terminal → 2, explicit config
  load failure → 1 with `load config <path>`, discovered broken → 1 with `load discovered config <path>`,
  discovered config prints `Using config`, explicit does not, picker cancelled → 0, picker `Rejected` → 1 with
  `install fonts: unsafe font family name "../x"`, `NoReleases` on the picker path → 2, one-family download failure
  prints the single `install fonts: install Nerd Font family <F>: download <url>: 404 Not Found` line → 1,
  `InterruptedException` from `installFonts` → 1 with `install fonts: interrupted`; `ConsoleEventRenderer` golden
  test for all eight events in `Ansi` and `Plain`; `OutputStyle` rules; `Application.run` unit tests assert the
  `Either[AppFailure, AppOutcome]` value per path without picocli.
- `app`: reflection config covers every `@Command` class; `Main` loads.
- CI smoke-tests the native binary: `--version`, `--help` (exit 0, usage on stdout), `--dry-run` with a sample
  config, and on ubuntu `kill -INT` mid-download against a local stub server asserting exit 1 and no
  `nerd-font-*.zip` / `.<Family>-*` left behind.

## 10. Deliverables outside the code

`README.md` (best-in-class, modelled on the Go README but truthful for this implementation),
`docs/ARCHITECTURE.md` (module map, invariants, decision log — including the `--help` and `h/l/f/d/u`
deviations), `docs/SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `config.example.yaml`, `scripts/install.sh`,
`.github/workflows/checks.yml` (fmt, scalafix, compile, tests on `ubuntu-24.04` + `macos-15`),
`.github/workflows/release.yml` (4 native images built by `./mill app.nativeImage` on a per-target runner matrix —
native-image cannot cross-compile and the Mill-fetched toolchain means no `setup-graalvm`/`setup-java` step:
`linux-amd64` → `ubuntu-24.04`, `linux-arm64` → `ubuntu-24.04-arm`, `macos-amd64` → `macos-15-intel`,
`macos-arm64` → `macos-15`; never `macos-latest` or the retired `macos-13`; tar.gz + sha256 per target; GitHub
Release on `v*` tags and a moving `latest` pre-release with stable asset names), `.github/dependabot.yml`,
`AGENTS.md` + `CLAUDE.md`. `app.writeAssembly` (JVM jar) is a local convenience only.

## 11. Implementation notes

The code is the reference for anything below; each item is a deliberate departure from, or refinement of, the
sections above, collected from the implementation commits and the integration pass. `docs/ARCHITECTURE.md`
carries the reasoning; `docs/PARITY.md` the measured comparison with Go.

- **§3.1 `ReleaseUrls` is a class over one `releases` base**, with `ReleaseUrls.github` as the production
  instance; `InstallPlan.of(request, urls)` and `FontInstaller(…, urls)` take it as a defaulted parameter. The
  composition root reads the undocumented test hook `NERD_FONTS_INSTALLER_BASE_URL` to point a run at a local
  stub (CI interrupt smoke); users never see it.
- **§3.1 / §6.3 `FamilyInstallError` has a `TempZip(cause)` case** (Go's `create temporary zip file: <cause>`)
  and `render(family)` takes the family so the checksum message can name it without every case carrying it.
- **§6.5 `FamilyInstallAborted` is raised in the flow's `runForeach`, not inside the worker**, because Ox wraps a
  worker's exception in `ChannelClosedException.Error`; the boundary still catches exactly one type.
- **§6.3 `ZipInputStream` consequences:** a file that is not a zip has no entries and reports
  `no font files found` rather than Go's `open font zip`; an entry without a declared size skips the declared
  check and relies on the `fontFile + 1` cap and the running total. `ArchiveEntryError.InvalidName` names a base
  name the filesystem cannot represent.
- **§3.1 `GitHubReleaseCatalogue` owns its 8 MiB page cap default** rather than reading `SizeLimits.apiPage`,
  which lives in the later-built `install` package.
- **§4 YAML:** a blank file, a comment-only file and a leading UTF-8 byte-order mark are all the empty document
  (validation then says `at least one font family is required`), where Go reports `parse <path>: EOF` for the
  first two. JSON keeps `encoding/json`'s rejection of a BOM. `multiple json values` is reported for any
  trailing content, slightly broader than Go.
- **§4 unknown-key wording** is `parse <path>: unknown field "<key>"` as §3.2 defines, not yaml.v3's two-line
  `field <key> not found in type config.Config`; prefix, stream and exit code match.
- **§4 `ReleaseTag.parse` and `DestinationPath.parse` return `Option`**; `InstallConfig.validated` turns absence
  into `release is required` / `destination is required`.
- **§7 `PickerStep` has a fourth case, `Cancelled`**, so `update` can ignore keys after the end without a flag.
  `KeyDecoder.Char` is a BMP `Char` (supplementary code points are dropped); unknown CSI/SS3 sequences are
  consumed whole; `Box` truncates with `…` instead of wrapping; the list window scrolls and the page indicator
  counts pages; the spinner pads with spaces rather than `ESC[K`. `h/l/f/d/u` remain unbound.
- **§8 `--help`** goes to stdout with exit 0 (as §1 states); a malformed command line prints picocli's own
  first line (`Unknown option: '--bogus'`) before the usage, where Go prints `flag provided but not defined`.
  Every option carries an explicit `order` so the usage text is Go's alphabetical order with `--help` last.
- **§6.3 temp directory:** `nerd-font-*.zip` is staged under `$TMPDIR` when set and non-empty, else the JDK's
  `java.io.tmpdir`, matching Go's `os.CreateTemp("", …)`.
- **§6.8 second SIGINT** halts the process with 130 (Go absorbs repeats).
- **§9 CI smoke:** `scripts/ci/interrupt-smoke.sh` implements the `kill -INT` scenario against a local Python
  stub and is invoked by `.github/workflows/checks.yml`.
- **§10** `README.md`, `SECURITY.md`, `CONTRIBUTING.md` and `CHANGELOG.md` are not yet written; `release.yml`
  packages `README.md` only when it exists.

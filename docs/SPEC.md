# nerd-fonts-installer-scala — implementation specification

Status: authoritative design, revised after an adversarial review (ZIO / native-image feasibility,
structure) and again for the migration from direct-style Scala (Ox + picocli) to a ZIO-native
application. Implementation agents follow this document; any departure from it must be listed in the
commit/PR description and reflected back here.

## 1. Goal

This tool installs [Nerd Fonts](https://github.com/ryanoasis/nerd-fonts) from a declarative config file
and ships as a GraalVM native binary for Linux (amd64, arm64). It resolves a
release, downloads one zip per font family from GitHub, verifies it against the release's `SHA-256.txt`,
extracts only font files into `<destination>/<Family>/` atomically, and optionally runs `fc-cache`.

Command surface and output contracts:

- Flags: `--config <path>`, `--dry-run`, `--font-names`, `--version`, `--help`.
  Only the double-dash spelling of a long flag is accepted (§7). `--help` prints usage to **stdout** and
  exits **0**.
- `--font-names` stdout format:
  ```
  # v3.4.0
  families:
    - 0xProto
    - 3270
  ```
- Exit codes: `0` success; `2` user-correctable input (malformed flags, no config found, unknown release tag, no releases at all);
  `1` everything else, **including config load/parse/validation errors** (explicit `--config`,
  `$NERD_FONTS_INSTALLER_CONFIG`, or a discovered candidate that exists but fails to load), network, filesystem,
  extraction, checksum mismatch, `fc-cache` failure, interruption. Only an unknown release tag, no releases,
  and no config found map to 2; every other application failure maps to 1.
- Config discovery order and the `NERD_FONTS_INSTALLER_CONFIG` env var (§4).
- Install layout and atomic replacement semantics (§6).

## 2. Technology and conventions

| Concern | Choice |
| --- | --- |
| Language / build | Scala 3.9.0, Mill 1.1.9 (`./mill`). JVM toolchain `graalvm-community:25.0.2` fetched by Mill (`jvmVersion` on every module, so `app.nativeImage` finds `native-image` without `GRAALVM_HOME`). Bytecode/API target = the toolchain (25): nothing ships as a JVM jar, so JDK 22+ APIs such as `Console.isTerminal` are available |
| Effects / concurrency | ZIO 2.1.26 throughout — `ZIO`/`IO` effects, `Scope` for resource safety, `ZIO.foreachPar`/`.withParallelism` for the fan-out, `.timeoutFail` for deadlines, `.ensuring`/`ZIO.acquireRelease` for cleanup, `Semaphore` to serialise the event sink. No Ox, no Futures |
| Entry point | `ZIOAppDefault` (`app.Main`); the whole program is one `ZIO` value the runtime executes |
| CLI parsing | A hand-rolled parser (§7). **Not** picocli, **not** zio-cli — §7 records why zio-cli was rejected |
| Config decoding | zio-config 4.1.0 (`zio-config-yaml`, `zio-config-typesafe`, `zio-config-magnolia`). YAML through the YAML provider; JSON and HOCON both through Typesafe Config (HOCON is a superset of JSON). Formats: JSON, YAML, HOCON only (§4) |
| Release/API JSON | `zio-json` (AST via `Json`), strict field typing |
| Filesystem | `os-lib` for paths and IO; `java.util.zip.ZipInputStream` for archives |
| HTTP | zio-http 3.11.5 client behind a streaming port (§3.1), configured with `ClientSSLConfig.FromJavaxNetSsl()` so TLS certificates are actually validated (a security invariant — §3.1) |
| Colour | `fansi` |
| Tests | zio-test (`zio-test`, `zio-test-sbt`, `zio-test-magnolia`); ports are faked, tests never hit the network |
| Lint | scalafmt, scalafix (OrganizeImports, DisableSyntax: no `var`, no `return`, no `null`, no `while`) |
| Compiler | `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement -Wsafe-init -source:future` |

Coding rules (from the direct-style Scala skill and Refactoring Guru; enforced in review):

- Braceless Scala 3 syntax everywhere. Explicit return types on every public member.
- Every top-level type declares intentional visibility: public API, `private[<pkg>]`, or `private[nerdfonts]`.
- Domain values are opaque types or enums, never raw `String`/`Boolean` (`FamilyName`, `ReleaseTag`,
  `ReleaseSelector`, `DryRun`, `RefreshFontCache`, `ByteLimit`, `Sha256Digest`, `ColourMode`).
  The one sanctioned raw `String` is `Release.families` (§5): untrusted upstream asset stems shown verbatim.
- Recoverable failures are `ZIO`/`IO` typed errors or `Either[E, A]` with sealed error ADTs per concern.
  Exceptions cross a boundary only as ZIO defects or as an interrupt (§6.8).
- No class-level `var`, `null`, `return` or `while`. A local `var` is acceptable only for
  a fold the compiler cannot express more clearly, and never with `while` (use `@tailrec` recursion or
  `Iterator`). The hand-rolled parser folds over the argument list without mutable state.
- Side effects live behind small port traits (`HttpClient`, `FontCacheRefresher`, `Environment`,
  `ProcessRunner`, `InstallEventSink`), with in-memory fakes in tests.
- Functions do one thing; orchestration reads as a sequence of named steps — a `for`-comprehension over
  `ZIO`/`Either` rather than a nested `flatMap` chain when three or more steps compose.
- Comments explain *why* (invariants, security reasoning, platform quirks), not *what*.
- Tests are targeted: one scenario per test, named as a sentence.

## 3. Module and package map

Mill modules and their allowed dependency edges (enforced by `moduleDeps`):

```
app -> cli -> { core, config }
config -> core
```

Root package: `io.worxbend.nerdfonts`. Packages are named after concepts.

### 3.1 `core` module

| Package | Owns |
| --- | --- |
| `io.worxbend.nerdfonts.fonts` | `FamilyName` (validated opaque type — **the single path-traversal guard**; `parse(raw): Either[FamilyNameError, FamilyName]`, `value`, `Ordering`), `ReleaseTag` (opaque, non-blank trimmed), `ReleaseSelector` (`Latest \| Tagged(tag)`; `parse(raw)` maps blank/`latest` → `Latest`; `render`), `DestinationPath` (opaque, non-blank trimmed, unexpanded), `RefreshFontCache` and `DryRun` two-case enums, `InstallConfig(selector, destination, refreshFontCache, families: Vector[FamilyName])` with `InstallConfig.validated(...)` enforcing §4, `ConfigValidationError` ADT rendering the validation messages |
| `io.worxbend.nerdfonts.releases` | `Release(name: String, tag: ReleaseTag, families: Vector[String])`, `ReleaseCatalogue` port (`def releases(): IO[ReleaseError, Vector[Release]]`), `GitHubReleaseCatalogue` adapter (paginated GitHub API client; each page fetched through `HttpClient.getString` with an 8 MiB cap, decoded by `ReleasePageDecoder` with zio-json, the whole page fetch wrapped in `.timeoutFail(ReleaseError.Http(Transport("timed out …")))(30.seconds)`), `ReleaseError` ADT (`NoReleases`, `NotFound(tag)`, `Http(HttpError)`, `Decode(message)`), `ReleaseSelection.select(releases, selector)`, `ReleaseUrls` (`download(selector, family): DownloadUrl`, `checksums(selector): Url`, path segments percent-escaped), `Url`/`DownloadUrl` opaque types, `Sha256Digest` opaque type (lowercase 64-hex; `parse`, `fromBytes`), `ChecksumManifest.parse(text): Map[FamilyName, Sha256Digest]` |
| `io.worxbend.nerdfonts.http` | `HttpClient` port — one streaming GET, so a body is never materialised by the port: `def get(request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject): ZIO[Scope, HttpError, HttpResponse]`, where `HttpResponse(body: ZStream[Any, HttpError, Byte])`. The `Scope` bounds the underlying connection's lifetime; callers wrap the whole read in `ZIO.scoped`. `ResponseDelivery` is the one place the response discipline lives (invariant 2): a non-2xx status → `HttpError.Status(code)` and an oversize `Content-Length` → `HttpError.TooLarge(limit, Some(declared))` are refused before a caller ever sees a body; `cappedBody` caps the stream at `limit` — `ZStream#take(limit.value)` for `Overflow.Truncate` (used only for the checksum manifest, §6.7), and a running total threaded through `chunks.mapAccumZIO` that fails with `HttpError.TooLarge(limit, None)` the moment a chunk would push past `limit` for `Overflow.Reject` (chunk-at-a-time, never per byte, so hashing a 768 MiB archive is not dominated by the cap). The production adapter and the in-memory fake both delegate to `ResponseDelivery`, so tests exercise the real cap logic. Extensions `getBytes` (collect the whole body within the cap) and `getString` (UTF-8) live on the companion. `HttpRequest(url: Url, headers: Map[String, String])` with a default `User-Agent: nerd-fonts-installer`. `ZioHttpClient` adapter over the zio-http `Client`: `ZioHttpClient.hardenedClient` builds the client from `ClientSSLConfig.FromJavaxNetSsl()` so TLS certificates are validated (invariant 8), streams with `ZClient.streaming` (never the body-materialising `batched`), and follows redirects with a small hand-rolled loop that drops the `Authorization` header the moment a redirect target's host differs from the request's. Transport failures and timeouts → `HttpError.Transport(cause)`. `HttpError` ADT (`Transport(cause: String)`, `Status(code: Int)`, `TooLarge(limit: ByteLimit, declared: Option[Long])`); `HttpError.Status#statusLine` renders `"<code> <reason>"` via `HttpStatus.reasonPhrase(code): Option[String]` (built-in IANA table covering registered 1xx–5xx codes; fallback `"<code>"`) because zio-http exposes only the numeric status, so the reason phrase is looked up from a built-in IANA table; **every** user-facing rendering of a non-2xx response goes through `statusLine`. `ByteLimit` opaque type over `Long` |
| `io.worxbend.nerdfonts.install` | `InstallRequest`, `InstallPlan` + `PlannedFamily` (pure output of `FontInstaller.plan`), `FontInstaller` (the engine), `ArchiveExtractor`, `DirectorySwap` (atomic replace with `.old` backup), `FontCacheRefresher` port + `FcCacheRefresher(processRunner)` adapter, `InstallEvent` (closed enum, eight cases, §6.9) + `InstallEventSink` (`def emit(event: InstallEvent): Unit`; contract: called from one thread at a time, in order — `FontInstaller` serialises the concurrent family fibers through a `Semaphore` permit, implementations must not add locking), `FamilyInstallError` ADT (`Download(url, HttpError)`, `Copy(url, tmp, cause)`, `ChecksumMismatch(actual, expected)`, `Staging(root, cause)`, `Extraction(zip, staging, ArchiveError)`, `Swap(SwapError)`, `TimedOut(limit)`), `InstallError` ADT (`Destination(root, cause)`, `Family(name: FamilyName, cause: FamilyInstallError)`, `FontCache(root, cause)`), `ArchiveError`, `SwapError`, `SizeLimits(download = 768 MiB, fontFile = 128 MiB, archive = 2 GiB, manifest = 1 MiB, apiPage = 8 MiB)` |
| `io.worxbend.nerdfonts.environment` | `Environment` port (`variable`, `homeDirectory: Option[os.Path]`, `workingDirectory: Either[EnvironmentError, os.Path]`) with `Environment.System` and `Environment.fixed`; `PathExpander.expand(path: DestinationPath, env): Either[PathError, os.Path]` (expands only a bare `~` and a leading `~/`; `~user`, an embedded `~` and everything else are returned unchanged; relative paths resolve against the working directory; an unresolvable home is `PathError.NoHome`); `ColourMode` enum (`Ansi \| Plain`) — the value every renderer consumes; detection lives in `cli` |
| `io.worxbend.nerdfonts.process` | `ProcessRunner` port (`run(spec: ProcessSpec): Either[ProcessError, ProcessResult]`, `lookPath(name: String): Option[os.Path]`), `ProcessSpec(command: Vector[String], stdin: Stdin = Stdin.Inherit, stdout: Stdout = Stdout.Inherit, stderr: Stderr = Stderr.Inherit)` with `enum Stdin { Inherit, FromFile(path) }`, `enum Stdout { Inherit, Capture }`, `enum Stderr { Inherit, Discard }` (`Inherit` = `ProcessBuilder.Redirect.INHERIT`, so `fc-cache` shares the real terminal), `ProcessResult(exit: ExitStatus, stdout: String)` (captured UTF-8 stdout, empty unless `Capture`), `ProcessError` ADT (`NotFound(command)`, `Failed(command, cause)`), `JdkProcessRunner` adapter (never a shell) |

### 3.2 `config` module — `io.worxbend.nerdfonts.config`

`ConfigLoader.load(path: os.Path): IO[ConfigError, InstallConfig]`, `ConfigDocument` (raw decoded shape,
`Option` fields, before defaults). Decoding is delegated to **zio-config 4.1.0**: `.yaml`/`.yml` through the
YAML provider (`zio-config-yaml`), `.json` and `.conf`/`.hocon` through Typesafe Config (`zio-config-typesafe`,
HOCON being a superset of JSON). zio-config does **not** read `ConfigDocument` directly: a `ConfigDto` case
class of `Option`s carries the magnolia-derived `Config[ConfigDto]`, and the decoded DTO is mapped onto the
unchanged `ConfigDocument`, which still owns the defaulting, normalization and validation sequence. An
unknown or missing extension is a hard error, not a silent guess (§4). Whichever format read the file, one
`ConfigDocument` is produced, then defaulted and validated once. `DiscoveredConfig(path: os.Path, config: InstallConfig)`
pairs a discovered path with its loaded config, `ConfigLocations.candidates(env): IO[ConfigError, Vector[os.Path]]`
(the ordered, de-duplicated candidate list), `ConfigDiscovery.discover(env, load): IO[ConfigError, Option[DiscoveredConfig]]`
(first candidate that exists is loaded; `None` when none exists; an existing candidate that fails to load
fails the effect — never skipped), `ConfigError` ADT (`NotFound(path)`, `Unreadable(path, cause)`,
`Parse(path, message)`, `UnsupportedFormat(path, extension)`, `Invalid(path, ConfigValidationError)`,
`NoWorkingDirectory(cause)`). `ConfigError.render` yields the detail only; `Application` adds the
`load config <path>: ` / `load discovered config <path>: ` prefixes (§4). No origin marker is carried on
`InstallConfig`: the branch taken in `Application.resolveConfig` decides whether `Using config <path>` is printed.

### 3.3 `cli` module — `io.worxbend.nerdfonts.cli`

- `Cli` — the process boundary: parses the argument list with the hand-rolled parser (§7), owns `--help`
  (usage to **stdout**, exit 0), `--version` (printed directly, exit 0) and unknown-flag errors (message to
  **stderr**, exit 2), turns parsed flags into an immutable `CliOptions`, runs `Application`,
  and maps the result through `ExitCode.of`. It never uses a CLI framework: neither picocli nor zio-cli (§7).
  The whole boundary is a `ZIO` value that `app.Main` (`ZIOAppDefault`) executes.
- `CliOptions(explicitConfig: Option[String], mode: CliMode, dryRun: DryRun)`
  with `enum CliMode { FontNames, Install }`. The raw `--config` string is kept, so an explicit empty path is
  echoed verbatim. `--font-names` selects `FontNames`; otherwise `Install`.
- `AppDependencies` — the seams between `Application` and the world:
  `loadConfig`, `discoverConfig`, `configCandidates`, `listReleases`, `installFonts`, `expandDestination`, plus
  `environment` and `colours`. The effectful seams are ZIO effects — `loadConfig`/`discoverConfig`/
  `configCandidates` (`IO[ConfigError, …]`, the config module now being ZIO), `listReleases`
  (`IO[ReleaseError, Vector[Release]]`) and `installFonts` (`IO[InstallError, Unit]`); the pure path-expansion
  seam returns `Either[PathError, …]`. Tests substitute a fake at each seam.
- `Application.run(options: CliOptions, deps: AppDependencies, out, err)`
  composed of `resolveConfig`, `printFontNames`, `selectRelease`, `install`; never sees `args` or
  the parser. Its result is `Either[AppFailure, AppOutcome]`.
- `enum AppOutcome { Installed, DryRunPrinted, FontNamesPrinted }`.
- `AppFailure` — failure ADT: `Config(ConfigError, prefixPath)`, `DiscoveredConfig(ConfigError)`, `NoConfig(hint)`,
  `Release(ReleaseError)`, `Destination(PathError)`, `Install(InstallError)`, `Interrupted(phase)`. Every case
  renders one stderr line.
- `ExitCode.of(result: Either[AppFailure, AppOutcome]): Int` — the only place application results become POSIX
  codes: any `Right` → 0; `NoConfig`, `Release(NotFound | NoReleases)` → 2; every other `Left`
  → 1. The exit 2 for a malformed/unknown flag and the exit 0 for `--help`/`--version` are produced by the
  hand-rolled parser (§7) and are the sole codes not routed through `ExitCode.of`.
- `OutputStyle.detect(env, consoleAttached): ColourMode` (`NO_COLOR`, `TERM=dumb`, `CLICOLOR_FORCE`/`FORCE_COLOR`
  escape hatches — same rules as binstaller's `CliOutputStyle`).
- `ConsoleEventRenderer(out, err, colours)` — the only place `InstallEvent`s become text; owns the event
  wording and the stdout/stderr routing (§6.9); exhaustive `match` with no wildcard; a plain writer with no
  locking, relying on the `InstallEventSink` contract.
- `TerminalProbe.isTerminal(): Boolean` — `Option(System.console()).exists(_.isTerminal)`. On the JDK 25 toolchain
  the default console provider (`java.base`) returns a console only when both stdin and stdout are TTYs, and
  `isTerminal` double-checks; no subprocess.
- `BuildInfo` (generated by Mill: `version`, `commit`, `buildDate`).

### 3.4 `app` module — `io.worxbend.nerdfonts.app`

`Main` only: the `ZIOAppDefault` entry point. The whole program is one `ZIO` value — `Main.run` provides the
dependency layers (the TLS-hardened zio-http `Client` via `ZioHttpClient.live`, the release catalogue, the
installer and the config loader) and executes `Cli`. SIGINT is handled by the ZIO runtime, which interrupts
the main fiber and runs every finalizer (§6.8), so a half-downloaded `nerd-font-*.zip` or `<root>/.<Family>-*`
staging directory is always cleaned up. The native image is this module's `NativeImageModule`; two of its
build flags are load-bearing and documented in `docs/ARCHITECTURE.md`: `--initialize-at-run-time=io.netty`
keeps Netty out of the image heap, and `-H:+SharedArenaSupport` is mandatory — without it the binary links
successfully and serves its first HTTP request correctly, printing the complete and correct output, but then
never exits, because Netty's shutdown path closes its off-heap arenas through `java.lang.foreign.Arena.ofShared`
and throws on every event-loop thread where nothing can catch it (details in `docs/ARCHITECTURE.md`). No
picocli reflection config is needed any more; the hand-rolled parser uses no reflection.

## 4. Configuration

Format is chosen by the file's last extension only, compared case-insensitively:
`.json` → JSON, `.yaml`/`.yml` → YAML, `.conf`/`.hocon` → HOCON. Any other extension — or none — is a hard
error, not a silent guess. It is a `ConfigError` like any other config-load failure and exits **1** (§1); exit
2 is reserved for command-line usage errors (an unknown flag, or no config found at all), not for a config file
that was found but could not be understood. Decoding is handled by
zio-config's providers: YAML through `zio-config-yaml`, and JSON and HOCON both through Typesafe Config
(`zio-config-typesafe`), because HOCON is a superset of JSON. JSON is therefore parsed leniently — comments and
unquoted keys are accepted rather than rejected. **Unrecognised keys are ignored, not rejected:** zio-config has
no strict-schema mode and emulating one would cost a second parse of every file, so the four known keys decode
normally and any other key is silently dropped. The honest practical cost is that a misspelled key such as
`familes:` is not reported as a typo — it leaves `families` at its default and surfaces one step later as
`at least one font family is required`. No security invariant depends on this: every family name is still
validated by `FamilyName.parse` before it reaches a path or URL. A scalar where a list is expected is silently
coerced, so `families: FiraCode` decodes as `Vector("FiraCode")` rather than failing. **Two breaking changes
from the pre-ZIO port:** `.conf` was previously decoded as YAML and is now
HOCON (a flat `key: value` file parses identically, but a YAML block sequence must become
`families = ["JetBrainsMono"]` or be renamed `.yaml`); and an unknown or missing extension is now an error
instead of a YAML guess.

| Key | Required | Default | Notes |
| --- | :-: | --- | --- |
| `release` | no | `latest` | `latest` or a tag such as `v3.4.0`; trimmed; blank after trim is an error |
| `destination` | no | `~/.local/share/fonts/NerdFonts` | trimmed; blank after trim is an error; `~` expanded by `Application` via `PathExpander` before the install request is built (§6.1) |
| `refresh_font_cache` | no | `false` | boolean; the DTO field carries a `@name("refresh_font_cache")` (`zio.config.derivation.name`) because magnolia binds field names verbatim (see below) |
| `families` | yes | — | list of strings; a bare scalar is coerced to a one-element list; each trimmed then validated by `FamilyName`; duplicates are an error |

Whichever provider reads the file, the four keys above are read into one `ConfigDocument`, the defaults are
applied, and `InstallConfig.validated` enforces the rules in `core` (blank-after-trim is an error, `families`
entries are each validated by `FamilyName`, duplicates are rejected, at least one family is required). Fine
scalar coercion (e.g. `families: [3270]` taken as the string `"3270"`, boolean spellings for
`refresh_font_cache`, a null leaving a default) follows the zio-config YAML provider and Typesafe Config; the
schema, defaults and validation messages are the contract and do not depend on the format.

Two mechanics of the zio-config binding are load-bearing and easy to break:

- **Snake-case is bound by annotation, not derivation.** magnolia applies no naming transform, so the DTO field
  names bind to the config keys verbatim. `release`, `destination` and `families` are single words and bind as
  written; `refresh_font_cache` is the one snake-case key, so its field carries `@name("refresh_font_cache")`.
  Dropping that annotation would not fail — the reader would silently find nothing and fall back to the
  `false` default, so the annotation must stay.
- **A malformed file throws at provider construction, before the load.** A syntactically broken YAML/JSON/HOCON
  file does not yield a `Config.Error`; the underlying library throws while the provider is being built
  (`ParserException` for YAML, `ConfigException$Parse` for HOCON/JSON). Provider construction is therefore
  wrapped in `ZIO.attempt` and mapped to `ConfigError.Parse`. A raw `Config.Error` is never printed directly —
  its `toString` embeds a ZIO fiber stack trace — so it is folded to a single line first.

Resolution order (highest first), implemented in `Application` + `ConfigLocations`:

1. `--config <path>` — explicit; load/parse/validation errors are fatal with exit 1 and the message
   `load config <path>: <cause>`.
2. `$NERD_FONTS_INSTALLER_CONFIG` (trimmed; blank falls through) — treated exactly like `--config` (same prefix,
   exit 1).
3. `./nerd-fonts-installer.{yaml,yml,json,conf,hocon}`
4. `./nerd-fonts-installer/config.{yaml,yml,json,conf,hocon}`
5. The same two shapes under `$XDG_CONFIG_HOME` when it is an absolute path, else under `~/.config`.

The candidate list is built in that order and de-duplicated preserving first occurrence (five extensions —
`yaml`, `yml`, `json`, `conf`, `hocon` — in two name shapes across two locations, so when cwd equals the
config home there are 10 candidates, not 20, in both discovery and the no-config hint). If the home directory
cannot be resolved (and `$XDG_CONFIG_HOME` is not absolute) the config-home candidates are silently omitted. If
the working directory cannot be determined, discovery fails with `locate current directory: <cause>` — exit 1
(also on the `--font-names` path).

Discovery (`ConfigDiscovery.discover`) succeeds with `Some(DiscoveredConfig(path, config))` for the first
candidate that exists, `None` if none exists, and fails with a `ConfigError` (fatal:
`load discovered config <path>: <cause>`, exit 1)
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
(`.timeoutFail(...)( 30.seconds)` around the whole page fetch); a timeout
surfaces as `ReleaseError.Http(HttpError.Transport("timed out after 30 seconds"))`. Stop when the **raw** page is
empty (not when filtering emptied it). Drop drafts and blank tags. Families = sorted unique asset names ending in
`.zip` (case-insensitive) minus the extension; drop releases with no families. Release `name` falls back to the tag.
The page body is decoded with zio-json (`ReleasePageDecoder`), strict about field types.

`Release.families` is `Vector[String]` — raw asset stems, deliberately **not** `FamilyName`: they are untrusted
upstream data used only by `--font-names`, which prints them verbatim, and the catalogue never drops or rejects
a stem. Configured installs cross the `FamilyName` boundary in the config loader.

Errors: empty result → `ReleaseError.NoReleases` (`no Nerd Fonts releases found`); unknown tag →
`ReleaseError.NotFound(tag)` (`nerd fonts release "<tag>" was not found`); non-2xx page →
`list Nerd Fonts releases: <statusLine>` (e.g. `list Nerd Fonts releases: 403 Forbidden`); transport failure →
`list Nerd Fonts releases: <cause>`; undecodable body → `decode Nerd Fonts releases: <cause>`.

`ReleaseSelection.select(releases, selector)`: `Latest` → first element; `Tagged(t)` → first with matching tag.

URL builders (path segments percent-escaped):

- latest: `https://github.com/ryanoasis/nerd-fonts/releases/latest/download/<Family>.zip`
- tagged: `https://github.com/ryanoasis/nerd-fonts/releases/download/<tag>/<Family>.zip`
- checksums: the same two shapes with `SHA-256.txt`

`ChecksumManifest.parse(text)`: each line `<hex>  <file>`; keep only `.zip` entries; digest lowercased; key is
the file name without extension parsed as `FamilyName` (lines that fail to parse are skipped — they can never
match a validated family). Read at most 1 MiB, **truncating** (not rejecting) anything beyond; a line cut by
the limit is skipped like any other unparseable line. Manifest fetch failure is a
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

Every progress/warning line the renderer emits carries the glyph prefix (`•` plan/warnings, `↻` dry-run cache,
`⠋` in progress, `✅` done). The glyph is part of the wording and is kept in `Plain` mode; only colour is dropped.

### 6.3 Real run

0. `os.makeDir.all(root)`; failure is `InstallError.Destination(root, cause)` → `create destination <root>: <cause>`
   and nothing else runs (no HTTP call is made). Then fetch the checksum manifest once (§6.7).

Per family (`installFamily(planned): IO[FamilyInstallError, Unit]`):

1. Emit `Started(family, url)` → `⠋ Installing Nerd Font <Family> from <url>`.
2. `httpClient.get(url, limits.download)` (768 MiB) under `ZIO.scoped`; the returned `HttpResponse.body`
   ZStream is drained chunk-at-a-time into a temp file `nerd-font-*.zip` under the temp directory while a
   SHA-256 `MessageDigest` hashes the same bytes (the archive is read once, never held in memory). Both size
   checks are the port's responsibility; the installer maps `HttpError` → `FamilyInstallError.Download(url, error)`
   (including a `TooLarge` that surfaces mid-stream) and a copy/IO failure → `Copy(url, tmp, cause)`.
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
7. The temp zip and staging dir are always removed via `.ensuring`, including on interruption (§6.8).

Each family has a 10-minute deadline: `installFamily` wraps its whole body (steps 1–7, including the
`.ensuring` cleanup) in `.timeoutFail(FamilyInstallError.TimedOut(familyDeadline))(familyDeadline)`. On overrun
ZIO interrupts the effect and its finalizers run before `TimedOut` is observed, so the cleanup has completed
when the `Left` is seen; the `Left` then travels the ordinary path and, through `ZIO.foreachPar`, cancels the
in-flight siblings.

Message shapes (`<cause>` is the nested error's text; platform wording may vary):

| Error | Rendered as |
| --- | --- |
| `InstallError.Family(family, e)` | `install Nerd Font family <Family>: <render(e)>` — the family name MUST appear |
| `InstallError.Destination(root, cause)` | `create destination <root>: <cause>` |
| `Download(url, Status(code))` | `download <url>: <statusLine>` (e.g. `download https://…/Hack.zip: 404 Not Found`) |
| `Download(url, Transport(cause))` | `download <url>: <cause>` |
| `Download(url, TooLarge(limit, _))` | `download <url>: exceeds <limit> byte limit` (the Content-Length variant `TooLarge(limit, Some(n))` renders `download <url>: size <n> bytes exceeds <limit> byte limit`) |
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

The fan-out is `ZIO.foreachPar(plan.families)(installFamily).withParallelism(min(4, n))`. Paths per family are
disjoint (`<root>/<Family>`, its own staging dir, its own `.old`), which is what makes this safe. A single
`Semaphore.make(1)` created for the run guards every `sink.emit`: each fiber takes the permit
(`semaphore.withPermit`) around its emit, so `InstallEventSink.emit` is invoked from one fiber at a time, in
order, and the sink stays a plain writer with no locking of its own. Events outside the fan-out (the checksum
warning, the font-cache lines) go through the same guarded `emit`. `ZIO.foreachPar` supplies fail-fast fan-out
semantics: the first family to fail interrupts the in-flight siblings — whose `.ensuring` finalizers remove
their scratch files — and finished families stay installed (§6.5).

### 6.5 Error propagation

A failing family must cancel in-flight siblings. `ZIO.foreachPar` provides fail-fast fan-out semantics with no
machinery of our own: `installFamily(family)`'s typed error is mapped to
`InstallError.Family(family.name, cause)`, and the first failure short-circuits the parallel combinator, which
interrupts the still-running fibers before returning. Their `.ensuring` finalizers run during that
interruption, so every scratch file is removed; already-completed families stay installed. The failure is an
ordinary typed `ZIO` error throughout — no private exception and no `catch` — and a deadline (§6.3) is just
another such error.

### 6.6 Font cache

When `refreshFontCache` is on and not dry-run: if `fc-cache` is not on `PATH`, emit `FontCacheUnavailable` and
succeed. Otherwise emit `RefreshingFontCache(root)`, run `ProcessSpec(Vector("fc-cache", "-f", root))` (all
streams `Inherit`), then emit `FontCacheRefreshed`. A non-zero exit or launch failure is
`InstallError.FontCache(root, cause)` → exit 1.

### 6.7 Checksum fetch

Fetched once before the fan-out via `httpClient.getString(checksumUrl, limits.manifest, Overflow.Truncate)`
under a 30 s `.timeoutFail`, acceptable because a manifest failure is only a warning. Any `HttpError`
or timeout → emit `ChecksumManifestUnavailable(cause)` and continue with an empty map, where `<cause>` is
`statusLine` for a non-2xx response and the transport text otherwise. A truncated body still yields every
complete line parsed before the cut. Any family absent from the map installs unverified; a present-but-mismatching
digest is fatal.

### 6.8 Interrupts (SIGINT)

SIGINT must interrupt cleanly: in-flight downloads abort through fiber interruption, and the program reports the
interruption as an install failure after cleanup. The JVM's default handler would exit 130 without unwinding
finalizers (and native-image is killed outright), which would leak `nerd-font-*.zip` and `<root>/.<Family>-*`
staging dirs; the ZIO runtime instead unwinds every `.ensuring`/`ZIO.acquireRelease` finalizer first. Therefore:

- The `ZIOAppDefault` runtime turns SIGINT into an interrupt of the program's main fiber. Interruption unwinds
  every finalizer, so each family's temp zip and staging dir are removed and an existing `<root>/<Family>` is
  left untouched.
- A second SIGINT while the first is still unwinding forces the process down immediately with 130 — an
  intentional escape hatch for a cleanup that itself hangs.
- The interrupt is reported as `AppFailure.Interrupted(phase)`: rendered `install fonts: interrupted` during the
  install, otherwise `interrupted`; exit **1**. An `InterruptedException` is never swallowed inside `core`.

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

Colours (ANSI mode only): spinner glyph 63, success glyph 42 bold, warning glyph 214, family
name 81 bold, url 39 underlined, path 219.

## 7. CLI

The command line is `nerd-fonts-installer [flags]`; there are no subcommands. Parsing is a small
**hand-rolled** parser, deliberately not a framework:

- **Not zio-cli.** zio-cli silently ignores an unknown flag and any single-dash flag and still exits 0, which
  would let a mistyped `-dry-run` perform a **real installation** instead of failing; it also has no
  `--version`. Both are unacceptable for a tool that writes to the filesystem, so the parser is written by hand
  (the decision log in `docs/ARCHITECTURE.md` records the evidence).
- **Not picocli.** picocli is a Java/reflection dependency the ZIO build no longer carries, and the
  hand-rolled parser needs no native-image reflection config.

Parser contract:

- Long flags use the **double-dash** form only: `--config <path>`, `--dry-run`, `--font-names`, `--version`,
  `--help`. A single-dash long flag such as `-dry-run` is **not** recognised and is an error, so it fails
  loudly rather than silently performing a real install.
- Repeated flags are **last-wins**: `--config a --config b` uses `b`.
- An **unknown flag** prints a message to **stderr** and exits **2**. `--help` prints usage to **stdout** and
  exits **0**. `--version` is implemented directly.

`--font-names` resolves the release from explicit/env/discovered config, else falls back to `latest` when no
config is discovered (unlike an install, which fails without a config), and prints the available font families
in YAML-ready shape (`# <tag>`, `families:`, `  - <stem>`) so a user can copy them into a config file. Because
it is the only command that reaches the network **without needing a config file**, it is what CI runs against
every native binary as the network smoke test (`scripts/ci/native-network-smoke.sh`) — `--version` and `--help`
never start a Netty event loop, so they cannot exercise the HTTP path (§8, §9, and the
`-H:+SharedArenaSupport` decision-log entry in `docs/ARCHITECTURE.md`).

`--version` prints `nerd-fonts-installer <version> (<commit>, <date>)`. All three are
compile-time constants in the generated `cli.BuildInfo` (`version`, `commit`, `buildDate`); `build.mill` derives
commit and date from `Task.Input` tasks (`git rev-parse --short=12 HEAD` or `unknown`; `Task.env`
`NERD_FONTS_INSTALLER_BUILD_DATE` or `unknown`). `release.yml` exports the date variable before invoking Mill.

In `Cli`: 1. parse `args`; a usage error → message on **stderr**, exit 2; `--help`/`--version` → print, exit 0.
2. Build `CliOptions`. 3. `ExitCode.of(Application.run(options, deps, out, err))`, printing the `AppFailure`
message on `Left` (prefixed `install fonts: ` for install failures). 4. An interrupt during the run →
`AppFailure.Interrupted` → 1 (§6.8).

In `Application.run(options)`: 1. `CliMode.FontNames` → resolve release from
explicit/env/discovered config (default `latest`), list, select, print → `Right(FontNamesPrinted)`;
errors: `NotFound`/`NoReleases` → 2, config load errors and everything else → 1. 2.
Resolve config (explicit → env → discovered); no config →
`AppFailure.NoConfig`. 3. Expand the destination, build the `InstallRequest`, install (or dry run) →
`Right(Installed)` / `Right(DryRunPrinted)`.

All informational progress goes to **stderr**; stdout carries only machine-readable output (`--font-names`,
dry-run plan lines, `fc-cache` output). Error lines are the bare rendered message. Implementations use
`PrintWriter`s created with `autoFlush = true`.

## 8. Testing strategy

Tests are **zio-test** (`zio.test.sbt.ZTestFramework`); every port is faked and no test touches the network.

- `core/fonts`: property test — anything `FamilyName` accepts is non-empty, contains no `/`, `\` or NUL, is not
  `.`/`..`, is not absolute, equals its own base name; the FamilyName acceptance/rejection tables.
- `core/http`: a non-2xx status is refused before a body is delivered; `Content-Length > limit` is refused
  before a body is delivered; a body of `limit + 1` bytes with no `Content-Length` fails the stream with
  `TooLarge` under `Overflow.Reject`; `Overflow.Truncate` yields the first `limit` bytes; `statusLine` for
  404/403/429 and an unregistered code (`599` → `"599"`). The in-memory fake serves `Map[Url, Response]` through
  the same `ResponseDelivery` the production adapter uses, so the ZStream cap logic is real.
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
  warns; fc-cache non-zero fails; interrupt during download (a fake `HttpClient` whose body stream blocks until
  the test interrupts the installing fiber) removes the temp zip and staging dir, leaves the pre-existing family
  dir intact, and does not deadlock; per-family deadline (tiny limit in test) yields `TimedOut` with cleanup done.
- `config`: defaults, trimming, blank-after-trim per field, duplicates, unrecognised keys ignored (a misspelled
  key falls back to the default), a bare scalar coerced to a one-element `families` list, per-format
  decoding (YAML, JSON, HOCON), `.conf`/`.hocon` decoded as HOCON, `.yaml`/`.yml` as YAML, `.json`/`.JSON` as
  JSON, an unknown or missing extension is a hard error (`ConfigError.UnsupportedFormat`, exit 1 like every
  other config-load failure), a malformed file mapped to `ConfigError.Parse` (single-line message, no fiber
  trace), discovery order, de-duplication when
  cwd = config home, XDG rules (absolute vs relative), missing home omits config-home candidates,
  existing-but-broken candidate is fatal.
- `cli`: golden tests through `Cli` with fake dependencies for every exit-code path: `--version` format,
  `--font-names` (latest, configured release, env override, discovered config, missing
  release → 2, no releases → 2, broken config → 1), unknown flag `--bogus` → 2 on **stderr**, a single-dash long
  flag (`-dry-run`) rejected → 2, repeated flag is last-wins, no config → 2 with the hint, explicit config
  load failure → 1 with `load config <path>`, discovered broken → 1 with `load discovered config <path>`,
  discovered config prints `Using config`, explicit does not, one-family download failure
  prints the single `install fonts: install Nerd Font family <F>: download <url>: 404 Not Found` line → 1,
  an interrupt from `installFonts` → 1 with `install fonts: interrupted`; `ConsoleEventRenderer` golden
  test for all eight events in `Ansi` and `Plain`; `OutputStyle` rules; `Application.run` unit tests assert the
  `Either[AppFailure, AppOutcome]` value per path without going through the parser.
- `app`: `Main` (the `ZIOAppDefault`) loads and its layers wire up.
- CI smoke-tests the native binary: `--version`, `--help` (exit 0, usage on stdout), `--dry-run` with a sample
  config, `--font-names` as the network smoke test (`scripts/ci/native-network-smoke.sh`) — the only command
  that reaches the network without a config file, so it is the one that proves the binary's HTTP path works;
  the check **asserts the exit code**, not just stdout, because a binary missing `-H:+SharedArenaSupport`
  prints correct output and then hangs (see `docs/ARCHITECTURE.md`) — and on ubuntu `kill -INT` mid-download
  against a local stub server asserting exit 1 and no `nerd-font-*.zip` / `.<Family>-*` left behind.

## 9. Deliverables outside the code

`README.md` (best-in-class),
`docs/ARCHITECTURE.md` (module map, invariants, decision log), `docs/SECURITY.md`, `CONTRIBUTING.md`,
`CHANGELOG.md`, `config.example.yaml`, `scripts/install.sh`,
`.github/workflows/checks.yml` (fmt, scalafix, compile, tests on `ubuntu-24.04`),
`.github/workflows/release.yml` (2 native images built by `./mill app.nativeImage` on a per-target runner matrix —
native-image cannot cross-compile and the Mill-fetched toolchain means no `setup-graalvm`/`setup-java` step:
`linux-amd64` → `ubuntu-24.04`, `linux-arm64` → `ubuntu-24.04-arm`; tar.gz + sha256 per target; GitHub
Release on `v*` tags and a moving `latest` pre-release with stable asset names), `.github/dependabot.yml`,
`AGENTS.md` + `CLAUDE.md`. `app.writeAssembly` (JVM jar) is a local convenience only.

## 10. Implementation notes

The code is the reference for anything below; each item is an implementation note or refinement of the sections
above, collected from the implementation commits and the integration pass. `docs/ARCHITECTURE.md` carries the
reasoning.

- **§3.1 `ReleaseUrls` is a class over one `releases` base**, with `ReleaseUrls.github` as the production
  instance; `InstallPlan.of(request, urls)` and `FontInstaller(…, urls)` take it as a defaulted parameter. The
  composition root reads the undocumented test hook `NERD_FONTS_INSTALLER_BASE_URL` to point a run at a local
  stub (CI interrupt smoke); users never see it.
- **§3.1 / §6.3 `FamilyInstallError` has a `TempZip(cause)` case** rendering
  `create temporary zip file: <cause>`, and `render(family)` takes the family so the checksum message can name
  it without every case carrying it.
- **§6.5 sibling cancellation is `ZIO.foreachPar`'s own**: the first family's typed error short-circuits the
  parallel combinator, which interrupts the in-flight fibers and runs their `.ensuring` finalizers; there is no
  private abort exception and no boundary `catch`.
- **§6.3 `ZipInputStream` consequences:** a file that is not a zip has no entries and reports
  `no font files found`; an entry without a declared size skips the declared check and relies on the
  `fontFile + 1` cap and the running total. `ArchiveEntryError.InvalidName` names a base name the filesystem
  cannot represent.
- **§3.1 `GitHubReleaseCatalogue` owns its 8 MiB page cap default** rather than reading `SizeLimits.apiPage`,
  which lives in the later-built `install` package.
- **§4 empty documents and BOM** now follow zio-config's YAML provider and Typesafe Config rather than the
  pre-ZIO scala-yaml/ujson stack; the outcome is unchanged — an empty or absent document leaves
  every key unset, so validation reports `at least one font family is required`.
- **§4 unrecognised keys are ignored, not rejected.** zio-config has no strict-schema mode, so a key the schema
  does not know is silently dropped and the known keys decode normally; a misspelled key falls back to its
  default and surfaces later as a validation message (e.g. `at least one font family is required`). No security
  invariant depends on it — `FamilyName.parse` still guards every family name before it reaches a path or URL.
- **§4 `ReleaseTag.parse` and `DestinationPath.parse` return `Option`**; `InstallConfig.validated` turns absence
  into `release is required` / `destination is required`.
- **§8 `--help`** goes to stdout with exit 0 (as §1 states); an unknown flag prints the hand-rolled parser's
  own message to stderr before the usage and exits 2. A single-dash long flag such as `-dry-run` is rejected
  the same way.
- **§6.3 temp directory:** `nerd-font-*.zip` is staged under `$TMPDIR` when set and non-empty, else the JDK's
  `java.io.tmpdir`.
- **§6.8 second SIGINT** halts the process with 130.
- **§9 CI smoke:** `scripts/ci/interrupt-smoke.sh` implements the `kill -INT` scenario against a local Python
  stub and is invoked by `.github/workflows/checks.yml`.
- **§10** `README.md`, `SECURITY.md`, `CONTRIBUTING.md` and `CHANGELOG.md` are not yet written; `release.yml`
  packages `README.md` only when it exists.

# Architecture

Status: covers every module — `core` (foundation packages and the install engine), `config`, `cli` and `app` — plus the CI scripts and workflows. The behavioural contract is [`SPEC.md`](SPEC.md) (with its
implementation notes). This file records how the code is shaped and which invariants must never move.

## Module map

```
app -> cli -> { core, config }
config -> core
```

`core` contains the domain, the ports and the install engine. It never imports a CLI framework, fansi or
terminal code; zio-http appears only inside its own `http` adapter (`ZioHttpClient`), never elsewhere in
`core`. Root package `io.worxbend.nerdfonts`; packages are named after concepts. The edges are `moduleDeps` in `build.mill`.

| Module | Third-party dependencies | Role |
| --- | --- | --- |
| `core` | zio, zio-streams, zio-json, zio-http, os-lib | Domain values, ports and adapters, release catalogue, install engine |
| `config` | zio-config (yaml, typesafe, magnolia) | YAML/JSON/HOCON decoding (unknown keys ignored), defaults, discovery |
| `cli` | fansi | Process boundary, hand-rolled parser, `Application`, exit codes, event renderer, composition root, generated `BuildInfo` |
| `app` | — | `Main` (`ZIOAppDefault`), native-image build; the `NativeImageModule` |

### `core` packages

| Package | Role | Public surface |
| --- | --- | --- |
| (root) | Shared helpers | `Diagnostics.describe(error)` (`private[nerdfonts]`): the `<cause>` text of every message, so no adapter renders a `null` or blank cause; `TerminalSafe.sanitize` (`private[nerdfonts]`): replaces every C0/C1 control character and `DEL` with `?` so upstream text (a family stem, a zip entry name) cannot inject a terminal escape into a rendered line |
| `fonts` | Validated domain values | `FamilyName` (+ `FamilyNameError`), `ReleaseTag`, `ReleaseSelector`, `DestinationPath`, `RefreshFontCache`, `DryRun`, `InstallConfig` (+ `InstallConfig.validated`, `ConfigValidationError`); `Quoting` (`private[nerdfonts]`) quotes control characters and backslashes in a rendered name |
| `environment` | The process environment as a port | `Environment` (`variable`, `property`, `homeDirectory`, `workingDirectory`; `System`, `fixed`), `EnvironmentError`, `PathExpander` (+ `PathError`), `ColourMode` |
| `http` | The one HTTP port and its adapter | `HttpClient` (+ `getBytes`, `getString`), `HttpRequest`, `HttpResponse`, `Url`, `ByteLimit`, `Overflow`, `HttpError` (+ `statusLine`), `HttpStatus`, `ResponseDelivery`, `ZioHttpClient` |
| `releases` | The Nerd Fonts release catalogue | `Release`, `ReleaseCatalogue`, `GitHubReleaseCatalogue`, `ReleaseError`, `ReleaseSelection`, `ReleaseUrls` (a value over one `releases` base; `ReleaseUrls.github` is production), `DownloadUrl`, `Sha256Digest`, `ChecksumManifest` |
| `process` | Subprocesses as a port | `ProcessRunner`, `ProcessSpec` (+ `Stdin`, `Stdout`, `Stderr`), `ProcessResult`, `ExitStatus`, `ProcessError`, `JdkProcessRunner` |
| `install` | The install engine | `InstallRequest`, `InstallPlan` (+ `InstallPlan.of`, `PlannedFamily`), `SizeLimits`, `InstallEvent` + `InstallEventSink`, `FontInstaller`, `ArchiveExtractor` (+ `ArchiveError`, `ArchiveEntryError`, `ExtractedCount`), `DirectorySwap` (+ `SwapError`), `FontCacheRefresher` (+ `FontCacheAvailability`, `FontCacheError`) with `FcCacheRefresher`, `InstallError`, `FamilyInstallError` |

Test-side fakes, public and reusable from every module's tests: `http.InMemoryHttpClient` (routes `Url` →
canned response, records requests, serves bodies through the same `ResponseDelivery` the production adapter
uses) and
`process.FakeProcessRunner` (prefix-matched scripts, records calls, fixed `lookPath` table).
The `install` suites add package-private helpers only (`FontZips` builds zips in memory, `RecordingSink` is a
deliberately unsynchronised sink, `GatedHttpClient` holds chosen requests behind a latch); other modules drive
the engine through `InstallEventSink` and the two public fakes.

### The install engine — `io.worxbend.nerdfonts.install`

`FontInstaller(http, tempDir, refresher, limits, familyDeadline, manifestTimeout, urls)` is the only class with
behaviour; everything else is a value, a port or a pure step. `urls` defaults to `ReleaseUrls.github` and is
the only thing that decides which host a run downloads from.

| Step | Owner | Shape |
| --- | --- | --- |
| Plan | `InstallPlan.of(request, urls)` | Pure: de-duplicate families (first occurrence wins), compute `urls.download(selector, family)` and `root / family` per family. `FontInstaller.plan` returns it; `install` executes exactly it |
| Dry run | `FontInstaller.describe` | `WouldInstall` per planned family, then `WouldRefreshCache(root)`; no port is touched |
| Root | `FontInstaller.createRoot` | `os.makeDir.all(root)`; failure is `InstallError.Destination` and nothing else runs |
| Manifest | `FontInstaller.fetchDigests` | `getString(urls.checksums(selector), limits.manifest, Overflow.Truncate)` under `.timeoutFail(30 s)`; any failure is the `ChecksumManifestUnavailable` warning and an empty map |
| Fan-out | `FontInstaller.runFanOut` | `ZIO.foreachPar(plan.families)(installFamily).withParallelism(min(4, n))`; a run-scoped `Semaphore.make(1)` guards every `sink.emit`. The first family to fail short-circuits `foreachPar`, which interrupts the in-flight fibers |
| Family | `FontInstaller.installFamilyNow` | `Started` → temp zip → download + SHA-256 → digest check → staging dir → `ArchiveExtractor.extract` → `DirectorySwap.replace` → `Installed`; the temp zip and staging dir are removed via `.ensuring`; `.timeoutFail(TimedOut)(familyDeadline)` wraps only the download-through-swap step, *inside* the temp zip's `.ensuring`, not the whole family body — see the decision log entry on `timeoutFail` and stream interruption for why this nesting is load-bearing |
| Font cache | `FontInstaller.refreshCache` | `refresher.availability` decides between the `FontCacheUnavailable` warning and `RefreshingFontCache` → `refresh(root)` → `FontCacheRefreshed`; `FcCacheRefresher` runs `fc-cache -f <root>` with every stream inherited |
| Caps | `SizeLimits` | `download` 768 MiB, `fontFile` 128 MiB, `archive` 2 GiB, `manifest` 1 MiB, `apiPage` 8 MiB — injectable so tests can lower them. Enforced by `ResponseDelivery` (ZStream capping) for bodies and by `ArchiveExtractor` for entries |
| Scratch removal | `Cleanup` (`private[install]`) | Best-effort `removeFile`/`removeTree`, used in `.ensuring` finalizers and after a swap's commit point; swallows non-fatal exceptions only, so an interrupt still propagates |

Deadlines and concurrency constants live on the `FontInstaller` companion: `maxConcurrentInstalls = 4`,
`defaultFamilyDeadline = 10 minutes`, `defaultManifestTimeout = 30 seconds`, temp zip name
`nerd-font-*.zip`.


### `config` module — `io.worxbend.nerdfonts.config`

| Type | Role |
| --- | --- |
| `ConfigLoader.load(path): IO[ConfigError, InstallConfig]` | The only route from a file to an `InstallConfig`: read (`NotFound` vs `Unreadable`), decode by extension through zio-config, default, validate through `InstallConfig.validated`. The config module is ZIO — `load`, `candidates` and `discover` are all effects |
| `ConfigDocument` | What the file said: four `Option` fields, text untrimmed. `validated` applies the defaults (`latest`, `~/.local/share/fonts/NerdFonts`, refresh off, no families) and delegates to `core` |
| `ConfigDto` (`private[config]`) | The magnolia-derived shape zio-config actually reads: a case class of `Option`s carrying `Config[ConfigDto]`. zio-config does **not** read `ConfigDocument` directly; the decoded DTO is mapped onto `ConfigDocument`. Field names bind verbatim (magnolia applies no naming transform), so the one snake-case key carries `@name("refresh_font_cache")` (`zio.config.derivation.name`); the other three (`release`, `destination`, `families`) are single words and need no annotation. Dropping the annotation would silently read nothing and fall back to the default, so it must stay |
| Decoding (zio-config 4.1.0) | Delegated to zio-config: YAML via `zio-config-yaml`, JSON and HOCON via Typesafe Config (`zio-config-typesafe`, HOCON being a superset of JSON), the `Config[ConfigDto]` derived by `zio-config-magnolia`. Whichever provider reads the file, one `ConfigDocument` results. **Unrecognised keys are ignored** (zio-config has no strict mode; emulating one costs a second parse), and a scalar where a list is expected is coerced, so `families: FiraCode` decodes as `Vector("FiraCode")`. A malformed file throws at provider construction (`ParserException` for YAML, `ConfigException$Parse` for HOCON/JSON) rather than yielding a `Config.Error`, so construction is wrapped in `ZIO.attempt` and mapped to `ConfigError.Parse`; a raw `Config.Error` (whose `toString` embeds a fiber trace) is never printed directly |
| `ConfigFormat` (`private[config]`) | Chooses the provider from the path's extension (the last extension, compared case-insensitively): `.json` → JSON, `.yaml`/`.yml` → YAML, `.conf`/`.hocon` → HOCON. Any other extension, or none, is `ConfigError.UnsupportedFormat` (§4) — **not** a silent YAML guess (a breaking change from the pre-ZIO port, where `.conf` and unknown extensions were decoded as YAML) |
| `ConfigLocations.candidates(env): IO[ConfigError, Vector[os.Path]]` | Ordered, de-duplicated candidate list (§4); also owns `appName`, `extensions` and `configVariable` (`NERD_FONTS_INSTALLER_CONFIG`) so `cli` shares the literals |
| `ConfigDiscovery.discover(env, load): IO[ConfigError, Option[DiscoveredConfig]]` | First existing candidate; `DiscoveredConfig(path, config)` pairs a discovered path with its loaded config |
| `ConfigError` | `NotFound`, `Unreadable`, `Parse`, `UnsupportedFormat`, `Invalid(ConfigValidationError)`, `NoWorkingDirectory`; `render` is the detail only, the CLI adds `load config <path>: ` / `load discovered config <path>: `. `NotFound` stays distinct because discovery skips only that case; every other case, including `UnsupportedFormat`, is fatal and exits 1 |

Test-side helper: `config.ConfigFiles.write(dir, name, text)` (package-private) writes fixtures into a suite's temp
directory.


### `cli` module — `io.worxbend.nerdfonts.cli`

`Cli` and `Application` split the process into two: `Cli` is the process boundary (the hand-rolled parser,
`--help`/`--version`, exit codes, interrupt handling) and `Application` is the run after flag parsing, a
function from `CliOptions` and `AppDependencies` to `Either[AppFailure, AppOutcome]` that never sees the parser
or the argument array.

| Type | Role |
| --- | --- |
| `Cli` | The process boundary. It parses `args` with the hand-rolled parser: a usage/unknown-flag error prints to **stderr** and exits 2; `--help` prints usage to **stdout** and `--version` prints the version line, both exit 0; otherwise it builds `CliOptions`, runs `Application`, prints the `AppFailure` line on `Left` and maps through `ExitCode.of`. An interrupt during the run becomes `AppFailure.Interrupted`. The composition root builds `AppDependencies.production(Environment.System, tempDir)` — `$TMPDIR` when non-empty, else `java.io.tmpdir`, made absolute. The whole boundary is a `ZIO` value that `app.Main` executes |
| The hand-rolled parser (`private[cli]`) | Folds the argument vector into `CliOptions` with no mutable state and no reflection: double-dash long flags only (a single-dash long flag is a usage error), last-wins on a repeated flag, `--help`/`--version` handled directly, an unknown flag is a usage error (stderr, exit 2). Chosen over picocli (a Java/reflection dependency) and zio-cli (which ignores unknown/single-dash flags and exits 0) — see the decision log |
| `CliOptions(explicitConfig: Option[String], mode: CliMode, dryRun)` with `enum CliMode { FontNames, Install }` | The immutable result of parsing; the raw `--config` text survives so `load config <path>` echoes what was typed |
| `AppDependencies` | Function-typed seams (`loadConfig`, `discoverConfig`, `configCandidates`, `listReleases`, `installFonts`, `expandDestination`) plus `environment` and `colours`; the effectful seams are ZIO effects. `production(env, tempDir)` is the composition root, building the TLS-hardened zio-http client via `ZioHttpClient.live`, `GitHubReleaseCatalogue`, `FontInstaller(http, tempDir, FcCacheRefresher(JdkProcessRunner(env)), urls = …)`, `ConfigLoader`/`ConfigDiscovery`/`ConfigLocations`, `PathExpander`, `OutputStyle.detect(env, TerminalProbe.isTerminal())`. It is also the only reader of the test hook `NERD_FONTS_INSTALLER_BASE_URL` (`AppDependencies.baseUrlVariable`): a non-blank value becomes `ReleaseUrls(Url(base))`, so the CI network smoke can aim the shipped binary at a local stub; blank or unset keeps `ReleaseUrls.github` |
| `Application` | `run` dispatches on `CliMode`; `printFontNames` (explicit/env/discovered release, else `latest`, never announced — the one command that reaches the network without a config file), `resolveConfig` (explicit → env → discovered with `Using config <path>` → no-config failure), `selectRelease` (empty listing is `NoReleases` whatever the selector), `install` (expand the destination, build the `InstallRequest`, run the engine through `ConsoleEventRenderer`) |
| `AppOutcome` | `Installed`, `DryRunPrinted`, `FontNamesPrinted` |
| `AppFailure` (+ `InterruptPhase`) | One stderr line per case and the only place the operation prefixes live: `Config(cause, rawPath)` → `load config <raw>: `, with `rawPath` also substituted back in for the absolute path `cause.render` would otherwise show (the raw `--config`/`$NERD_FONTS_INSTALLER_CONFIG` path is never absolutised, so both the prefix and the wrapped error echo the same raw text; `Application.resolvePath` treats an empty raw path as `NotFound` outright rather than resolving it to the working directory); `DiscoveredConfig(cause)` → `load discovered config <path>: ` (bare for `NoWorkingDirectory`; no raw-path substitution, since a discovered candidate has no separate raw spelling), `NoConfig(candidates)` → the two hints, `Release`, `Destination`/`Install` → `install fonts: `, `Interrupted(Install | BeforeInstall)` |
| `ExitCode.of` | `Right` → 0; `NoConfig`, `Release(NotFound | NoReleases)` → 2; every other `Left` → 1 |
| `OutputStyle.detect(env, consoleAttached)` | `NO_COLOR` and `TERM=dumb` always win; otherwise a console or `CLICOLOR_FORCE`/`FORCE_COLOR` (non-empty, not `0`) enables `Ansi` |
| `ConsoleEventRenderer(out, err, colours)` | The only `InstallEventSink` in production: exhaustive match over the eight events, plan lines to stdout, everything else to stderr, the selected colours 63/42/214/81/39/219 through fansi in `Ansi` only, no locking |
| `TerminalProbe.isTerminal()` | `Option(System.console()).exists(_.isTerminal)` |
| `Interruptible` (`private[cli]`) | Where a fiber interrupt during the run is turned into `AppFailure.Interrupted(phase)` so the rendered line and exit 1 are produced, rather than a stack trace |
| `BuildInfo` | Generated by `build.mill` (`version`, `commit`, `buildDate`) |

Test-side helper: `cli.Fakes` (package-private) builds an `AppDependencies` whose every seam is a fake, and
`Fakes.run(deps, args*)` captures both streams and the exit code.

**Exit-code table** (the union of the parser's and `ExitCode.of`):

| Situation | Code | Produced by |
| --- | --- | --- |
| success, dry run, `--font-names` | 0 | `ExitCode.of(Right(_))` |
| `--help`, `--version` | 0 | the hand-rolled parser |
| unknown flag, single-dash long flag, missing option value | 2 | the hand-rolled parser (message to stderr) |
| no config, unknown release tag, no releases | 2 | `ExitCode.of` |
| everything else: config load/parse/validation, network, filesystem, extraction, checksum, `fc-cache`, interrupt | 1 | `ExitCode.of` |


### `app` module — `io.worxbend.nerdfonts.app`

`Main` is a `ZIOAppDefault`: its `run` provides the dependency layers (the TLS-hardened zio-http `Client` via
`ZioHttpClient.live`, the release catalogue, the installer, the config loader) and executes `Cli` as one `ZIO`
value. SIGINT is handled by the ZIO runtime, which interrupts the main fiber and unwinds every finalizer
(§6.8 of `SPEC.md`), so a half-downloaded `nerd-font-*.zip` or `<root>/.<Family>-*` staging directory is always
removed; a second SIGINT forces the process down with 130. The hand-rolled parser needs no reflection, so this
module ships no picocli reflection config.

### CI scripts and workflows

| File | Role |
| --- | --- |
| `.github/workflows/checks.yml` | fmt check, scalafix `--check`, compile, tests, then `app.run` from source (`--version`, `--help`, the example dry run) on `ubuntu-24.04`; a native-image smoke job on linux-amd64 (`--version`, `--help`, `--dry-run`, then the `--font-names` network smoke and the interrupt smoke); actionlint |
| `.github/workflows/release.yml` | Two native images on per-target runners (`ubuntu-24.04`, `ubuntu-24.04-arm`), tests on each, tar.gz + sha256 per target, `checksums.txt` + `install.sh` attached, a GitHub Release on `v*` tags (or a `workflow_dispatch` naming an existing tag) and a moving `latest` pre-release refreshed on every push to `main` |
| `.github/dependabot.yml` | Weekly grouped updates for GitHub Actions only; library versions are pinned by hand in `build.mill` |
| `scripts/ci/native-network-smoke.sh <binary>` | Proves the native binary's HTTP path works end to end: it runs the binary with `--font-names` (the only command that reaches the network without a config file) against a stub release API and **asserts the exit code**, not just the output — because a binary built without `-H:+SharedArenaSupport` prints correct output and then hangs on shutdown (see the decision log), a stdout-only check would pass on a broken binary. The timeout wrapper escalates to SIGKILL, since the hung process ignores SIGTERM |
| `scripts/ci/interrupt-smoke.sh <binary>` | §6.8 end to end against the real binary: a Python stub answers the manifest with 404 and streams an endless `/latest/download/Hack.zip`; the binary runs with `NERD_FONTS_INSTALLER_BASE_URL` and `TMPDIR` pointed at a scratch directory, receives SIGINT one second in, and must exit 1 with `install fonts: interrupted`, leaving no `nerd-font-*.zip`, no `.Hack-*` and no `Hack` directory. Runs under `set -m` so the background binary is not started with SIGINT ignored |
| `scripts/install.sh` | The end-user installer published with every release: detects OS and architecture, downloads the tarball and `checksums.txt` over TLS 1.2+, verifies, installs into `~/.local/bin` |

Application arguments follow `app.run` directly (`./mill --no-daemon app.run --help`): with this
repository's wrapper everything after a `--` separator is dropped, and a bare `app.run` discovers the
developer's own config and installs from it.

## Invariants

1. **The family-name trust boundary.** `FamilyName.parse` is the single path-traversal guard. It rejects the
   empty string, `.`, `..`, any `/` or `\`, NUL, absolute paths and anything whose base name differs from
   itself, with the documented messages verbatim. Every family name touches a path or a URL only as a `FamilyName`.
   `Release.families` is deliberately `Vector[String]`: raw upstream asset stems are display data, and the
   conversion happens exactly once, where a selection becomes an `InstallConfig`.
2. **Every body is capped.** `HttpClient.get` returns a scoped `HttpResponse` whose body is a
   `ZStream[Any, HttpError, Byte]`; bodies are never materialised by the port. `ResponseDelivery` is the one
   implementation of the response discipline (non-2xx and oversize `Content-Length` refused before the stream
   is read, body closed on every path via the response `Scope`, overflow raising `HttpError.TooLarge(limit,
   declared)` unless `Overflow.Truncate` was requested, in which case the stream is `take`-truncated). The
   zio-http adapter and the in-memory fake both delegate to it, so tests exercise the production cap logic.
   `Overflow.Truncate` is for the checksum manifest only.
3. **Checksum policy.** Manifest fetch failure is a warning; a present-but-mismatching digest is fatal. The
   manifest parser skips anything it cannot parse (non-zip lines, unsafe stems, non-64-hex digests, a line cut
   by the read limit); an absent entry means "install unverified", never "fail".
4. **Deadlines belong to callers.** `ZioHttpClient` configures only a connection timeout on the underlying
   zio-http `Client`. `GitHubReleaseCatalogue` wraps each page (connect, headers, body, decode) in
   `.timeoutFail(…)(30.seconds)`; the install engine owns its own per-family deadline through the same
   combinator. There is no throwing timeout to misuse.
5. **Interrupts are never swallowed.** A fiber interrupt propagates out of every port and unwinds the
   response `Scope` and every `.ensuring`/`acquireRelease` finalizer, so a half-written staging directory or
   partial download is always removed. `JdkProcessRunner` forcibly destroys a still-running child and waits,
   bounded, for it to be reaped before the interrupt propagates: a captured-stdout read is a classic pipe read
   that ignores interruption, so its destroy-and-EOF cleanup runs as a finalizer.
6. **No shell, ever.** `ProcessSpec.command` is an argv vector handed to `ProcessBuilder`; `Inherit` streams map
   to `Redirect.INHERIT` so `fc-cache` shares the real terminal.
7. **Nothing below the composition root reads `sys.env` / `sys.props`.** `Environment.System` is the only
   place that does; everything else receives an `Environment`. The composition root itself (`Cli.tempDir`,
   `AppDependencies.production`) consults `TMPDIR`, `java.io.tmpdir` and the base-URL hook through that port.
8. **Every user-facing rendering of a non-2xx response goes through `HttpError.Status#statusLine`**, which adds
   the reason phrase the HTTP client does not expose (`404 Not Found`, unregistered codes render bare).
9. **One loader for every config route.** `--config`, `$NERD_FONTS_INSTALLER_CONFIG` and every discovered
   candidate go through `ConfigLoader.load`; defaults and validation are applied exactly once, in
   `ConfigDocument.validated`, whichever format produced the document. Decoders never see defaults.
10. **Discovery skips only `NotFound`.** A candidate that exists but cannot be read, parsed or validated fails
    the effect, never skipped.
11. **Lenient keys, unknown extension fatal.** Decoding is delegated to zio-config across JSON, YAML and HOCON,
    which has no strict-schema mode: an unrecognised key is ignored (the known keys decode normally) and a
    scalar where a list is expected is coerced (`families: FiraCode` → `Vector("FiraCode")`). The safety net is
    elsewhere — every family name still passes `FamilyName.parse` (invariant 1) — so leniency here costs a
    misspelled key a helpful error, not a security guarantee. Extension selection, by contrast, is exhaustive:
    `.json`/`.yaml`/`.yml`/`.conf`/`.hocon` map to their formats and any other extension is
    `ConfigError.UnsupportedFormat`, never a silent YAML guess.
12. **Installs are staged, then renamed.** A family is extracted into `<root>/.<Family>-<random>` and becomes
    live only through `DirectorySwap.replace`: remove a stale `<target>.old`, rename the existing target to
    `.old`, rename the staging directory into place. The second rename is the commit point: before it a
    failure restores the previous fonts, after it a cleanup failure is never reported. An existing
    `<root>/<Family>` is therefore untouched by a failed download, a checksum mismatch, a bad archive, a
    deadline or an interrupt.
13. **Per-family paths are disjoint, which is what makes the fan-out safe.** `<root>/<Family>`, its staging
    directory and its `.old` belong to one family; `InstallPlan.of` de-duplicates so two workers never share
    them. At most four families run at once.
14. **The sink is serialised by the engine, never by the sink.** Workers emit through a `Semaphore.make(1)` +
    `withPermit`, so `InstallEventSink.emit` is invoked from one fiber at a time, in submission order, and each
    worker suspends until its line is written. Events outside the fan-out go to the sink on the calling fiber.
    Implementations must not add locking; the test sink is deliberately unsynchronised so a broken contract
    shows up as corruption.
15. **First failure cancels the siblings; finished families stay installed.** `ZIO.foreachPar` fails fast: the
    first family's `Left`/error ends the fan-out and interrupts the in-flight workers, whose finalizers remove
    their scratch files. Fiber interruption is never swallowed, so SIGINT unwinds every finalizer and exits
    through the CLI.
16. **Deadlines are values.** Each family runs under `.timeoutFail(TimedOut)(familyDeadline)`; the manifest
    fetch under `.timeoutFail(…)(manifestTimeout)`. There is no throwing timeout, so a deadline can never
    masquerade as a defect.
17. **Extraction trusts nothing in the archive.** Entries are chosen by extension (`.ttf`/`.otf`/`.ttc`,
    case-insensitive) and flattened to their base name, so a path inside the zip never decides where a byte
    lands; the declared size is refused before inflating, the stream is capped at `fontFile + 1`, the total
    at `archive`; every file is fsynced and closed explicitly; zero font files is an error.
18. **Upstream text is sanitised before it reaches a terminal, in every colour mode.** `FamilyName.parse`
    deliberately allows control characters, and a release tag, family stem or zip entry name is untrusted text
    that may reach a display before (or without ever passing through) any validation. `TerminalSafe.sanitize`
    (`core`, package `io.worxbend.nerdfonts`) replaces every C0 control character, `DEL` and the C1 range
    one-for-one with `?` — length-preserving for callers that track display positions. It runs in
    `ConsoleEventRenderer.paint` (both colour modes, ahead of `fansi`) and in `ArchiveError`/`ArchiveEntryError.render`
    for every embedded entry name.
19. **`AppFailure.render` is the only place the top-level `load config`/`load discovered config`/`install fonts`
    wording is added — the ADTs it wraps may already carry a prefix of their own.** `load config <path>: `,
    `load discovered config <path>: ` and `install fonts: ` are spelled once, in `cli`, so the same `ConfigError`
    reads differently depending on how the file was chosen. `Release` adds no prefix at all: `cause.render` is used verbatim. But `ReleaseError`, `PathError` and `ConfigError` are themselves already user-facing,
    operation wrappers one level down and do embed an operation prefix for the step *they* represent —
    `list Nerd Fonts releases: `, `locate current directory: `, `open <path>: `/`read <path>: `/`parse <path>: ` —
    so a rendered line can carry two prefixes chained together (e.g. `load config <path>: open <path>: no such
    file or directory`), not just `AppFailure`'s own.
20. **Exit codes come from `ExitCode.of` or from the hand-rolled parser, nowhere else.** `Application` returns
    values; no step chooses a number. The union of the two sources is the exit-code table above.
21. **`Application` never sees the argument array.** Parsing stops at `Cli`; everything below receives
    `CliOptions` and `AppDependencies`, which is what makes every §9 `cli` scenario a test on writers and an `Int`.

## Conventions that reviewers enforce

Braceless Scala 3; explicit return types on public members; opaque types and enums for domain values; `Either`
with a sealed error ADT per concern, each with a `render` that yields the message without any operation
prefix (the caller adds `download <url>: `, `list Nerd Fonts releases: ` and so on); no class-level `var`;
no `null`, `return`, `while` or mutable collections outside test fakes;
one concern per function; scaladoc on public types explains why, not what.

## Decision log

- **ZIO 2.1.26, not direct-style Ox.** The application is a ZIO program end to end: `Main` is a `ZIOAppDefault`,
  every port returns a `ZIO`/`ZStream`, cancellation is fiber interruption and cleanup is `.ensuring`/
  `acquireRelease`. This replaced the earlier direct-style stack (Ox structured concurrency, `either:`/`.ok()`
  error handling, a loan-shaped `HttpClient`). One effect system gives the fan-out (`ZIO.foreachPar` +
  `withParallelism`), the per-family deadlines (`.timeoutFail`), the event serialisation (`Semaphore`) and the
  interrupt-safe finalizers a single, well-specified interruption model instead of hand-maintained `finally`
  blocks and a private abort exception.
- **The zio-http client is pinned to validate TLS (`ClientSSLConfig.FromJavaxNetSsl()`).** zio-http's
  `Client.default` does **not** validate server certificates — empirically it completes a request to
  `https://expired.badssl.com/` — so `ZioHttpClient.hardenedClient` configures `ClientSSLConfig.FromJavaxNetSsl()`
  to use the JDK's default trust store. This is a security invariant: a plain `Client.default` would silently
  accept a forged or expired certificate on every download and manifest fetch, defeating the checksum and
  path-safety guarantees that assume the bytes came from GitHub. The flag must never be dropped.
- **`HttpError.TooLarge` carries an optional `Content-Length`.** One `TooLarge` case carries the declared size so
  the tool prints `size <n> bytes exceeds <limit> byte limit` when it rejected on the `Content-Length` header
  and `exceeds <limit> byte limit` when the stream overran — both renderings without a second error type.
- **`ReleaseTag.parse` and `DestinationPath.parse` return `Option`.** Both sources (config, GitHub API) treat a
  blank value as "absent" rather than as an error; `InstallConfig.validated` turns absence into the
  `release is required` / `destination is required` messages.
- **`GitHubReleaseCatalogue` owns its page cap default (8 MiB).** `SizeLimits` lives in `install`, which is
  built after this package; the engine passes its own value if it wants a single source of truth.
- **Control characters and backslashes in a family name are quoted (`Quoting`)** so a name containing NUL or a
  backslash renders escaped and never reaches the terminal raw.
- **JSON decoding is strict.** A `tag_name` that is not a string, or `assets` that is not an array, is a
  `ReleaseError.Decode` rather than silently coercing.
- **Config decoding is delegated to zio-config 4.1.0.** The hand-rolled `ConfigNode`/`ConfigFieldDecoder` tree
  and its per-format adapters are gone. YAML is read by `zio-config-yaml`, JSON and HOCON by `zio-config-typesafe`
  (Typesafe Config, HOCON being a superset of JSON). zio-config does not read `ConfigDocument` directly: a
  `ConfigDto` case class of `Option`s holds the magnolia-derived `Config[ConfigDto]`, and the decoded DTO is
  mapped onto the unchanged `ConfigDocument`, which keeps the `ApplyDefaults` + `Normalize` + `Validate`
  sequence and its one validation path (`ConfigDocument.validated`), whichever provider read the file.
- **Unknown keys are accepted, not rejected.** zio-config has no strict-schema mode and emulating one (a second
  parse to diff the key set) was judged not worth the cost, because nothing downstream depends on it: every
  family name is validated by `FamilyName.parse` before it touches a path or URL (invariant 1), so a stray key
  cannot smuggle anything past the trust boundary. The accepted price is that a misspelled key such as
  `familes:` is silently ignored and surfaces later as `at least one font family is required` rather than as a
  spelling error; a scalar where a list is expected is likewise coerced (`families: FiraCode` →
  `Vector("FiraCode")`). `ConfigError.UnknownField` and `ConfigError.WrongType` became unreachable and were
  deleted.
- **A malformed config file throws at provider construction, not as a `Config.Error`.** The YAML and Typesafe
  Config parsers raise (`ParserException`, `ConfigException$Parse`) while the provider is being built, before
  the load runs, so provider construction is wrapped in `ZIO.attempt` and mapped to `ConfigError.Parse`. A raw
  `Config.Error` is never rendered directly — its `toString` embeds a ZIO fiber stack trace — so it is folded to
  a single line first.
- **Format is chosen by extension, and an unknown extension is fatal.** `.json` → JSON, `.yaml`/`.yml` → YAML,
  `.conf`/`.hocon` → HOCON (the last extension, case-insensitive); any other extension, or none, is
  `ConfigError.UnsupportedFormat`, which like every other `ConfigError` exits 1 (exit 2 is reserved for
  command-line usage errors). This is a deliberate breaking change from the pre-ZIO port, where `.conf` and
  every unrecognised extension were decoded as YAML — a silent guess that could parse a HOCON file as YAML and
  mis-report the failure.
- **`ConfigError` cases carry the path and decoders receive it.** One error ADT for read, decode, validate and
  discovery avoids a second decode-error type that would be remapped case by case in `ConfigLoader`. `NotFound`
  is kept deliberately distinct from the rest because discovery skips only that case.
- **`Environment` is the only input to discovery.** `ConfigLocations` never reads `sys.env`; `$XDG_CONFIG_HOME` counts
  only when absolute; a missing home silently drops the config-home half, a missing working
  directory is `ConfigError.NoWorkingDirectory` rendered `locate current directory: <cause>`.
- **The fan-out fails fast through `ZIO.foreachPar`.** A family's `Left` ends the fan-out and interrupts the
  in-flight workers, whose finalizers remove their scratch files; there is no private exception type crossing a
  scope, as there was under Ox. The §6.5 shape (first failure cancels the siblings, finished families stay
  installed) is `foreachPar`'s own semantics, bounded by `withParallelism`.
- **`.timeoutFail` must not wrap a resource's own cleanup when a `ZStream` is involved.** `installFamily` used to
  be `installFamilyNow(...).timeoutFail(TimedOut)(familyDeadline)`, with the temp-zip `.ensuring(Cleanup.removeFile)`
  nested *inside* that timeout (in `withTempZip`, called from `installFamilyNow`). Under `ZIO.foreachPar`, a
  failing sibling interrupting an in-flight family reproducibly (not rarely — around 85% of runs) left the temp
  zip behind: `foreachPar`'s `_.interrupt` returned before the download's `.ensuring` cleanup had actually run.
  Root cause, confirmed with a standalone reproduction outside this codebase: `.timeoutFail` is built on
  `raceFibersWith`, which forks the guarded effect as a genuine child fiber and waits on it through a plain,
  non-cancellable `ZIO.async`. When the fiber running the *whole* `.timeoutFail(...)` expression is interrupted
  externally while the raced effect is a `ZStream` pull (as `copyHashing`'s `body.runForeachChunk` is), that
  fiber's own suspension in the `async` unwinds early — before the forked child fiber's stream-side
  finalizers have finished — so the interrupter observes completion before cleanup actually lands. This does not
  happen with a non-stream blocking effect (e.g. `ZIO.attemptBlockingInterrupt`) under the same interrupt, and
  does not happen without `.timeoutFail` in the mix; it needs both together. The fix moves `.timeoutFail` to wrap
  only `fetchAndStage` *inside* `withTempZip`'s `use`, so `withTempZip`'s `.ensuring` sits outside the timeout, on
  the same fiber `foreachPar` directly interrupts and awaits — the ordering `foreachPar` relies on then holds.
  `withStaging` (one level deeper) was left as-is: `ArchiveExtractor.extract`/`DirectorySwap.replace` run through
  a single `ZIO.attemptBlockingIO`, never a `ZStream`, so it does not hit this failure mode. Lesson for future
  work in this codebase: never let `.timeoutFail`/`.timeout`/`race*` wrap an effect that both (a) uses zio-streams
  internally and (b) has a resource-cleanup finalizer that must be observed by an external interrupter — put the
  finalizer outside the race instead.
- **`ZipInputStream`, as the spec chose, with its two consequences accepted.** A file that is not a zip has no
  entries and is reported as `no font files found`; a corrupt header mid-stream is `ArchiveError.Open`. A
  streamed entry may not declare its size (`-1` when it lives in the data descriptor),
  so the declared checks are skipped for it and the `fontFile + 1` cap plus the running total after the copy
  are the guards; `zip`-built Nerd Fonts archives declare sizes, so production still fails fast.
- **`FamilyInstallError.TempZip` is one case more than the spec lists.** Creating `nerd-font-*.zip` can fail
  before any URL or path is known; this case reports `create temporary zip file: <cause>`,
  instead of mis-filing the failure under `Copy`.
- **`FamilyInstallError.render(family)` takes the family.** Only `checksum mismatch for <Family>` needs the name
  and only the `InstallError.Family` wrapper holds it; passing it beats duplicating it in every case.
- **`FontCacheRefresher` has two members.** `availability` and `refresh(root)` are separate so the engine can emit
  `FontCacheUnavailable` versus `RefreshingFontCache` → `FontCacheRefreshed` itself; a single `refresh` returning a
  tri-state would push event ordering into the adapter. `FontCacheError` is typed (`Launch(ProcessError)` /
  `Exit(ExitStatus)`) and renders `exit status 1`.
- **`ArchiveEntryError.InvalidName`** covers a base name the filesystem cannot represent (a NUL byte in a hostile
  archive); it is named so the message stays readable.
- **`Cleanup` is best effort by design.** Removing the temp zip, the staging directory and a committed swap's
  `.old` swallows non-fatal exceptions only, so an interrupt still propagates while a leftover can never mask
  the real error or fail a successful install.
- **`--help` goes to stdout and exits 0.** `--help | less` is what people do, so usage goes to stdout with exit
  0; `-h`, `-help` and `--help` are all accepted, and there is no `-V`.
- **The exit-code table is the union of the hand-rolled parser's and `ExitCode.of`.** The parser produces 2 for a
  malformed command line (message to stderr) and 0 for `--help`/`--version`; every other code is `ExitCode.of`
  (`errNoConfig`, `ErrNoReleases` and `ReleaseNotFoundError` are 2). Both sources name their codes from one
  `ExitCode` value set so the two cannot drift.
- **A hand-rolled argument parser, not zio-cli or picocli.** zio-cli was rejected on concrete evidence: it
  silently ignores an unknown flag and a single-dash long flag and still exits 0, so a mistyped `-dry-run` would
  perform a *real* installation instead of the intended dry run, and it offers no `--version`. picocli was
  rejected because it is a Java, annotation-and-reflection dependency that forces a mutable option class and a
  native-image reflection config. The parser is ~a screen of pure code: double-dash long flags only, last-wins
  on repeats, `--help`/`--version` handled directly, and an unknown or
  single-dash flag is a usage error printed to stderr with exit 2. It has no mutable state and needs no
  reflection metadata.
- **SIGINT is a ZIO fiber interrupt, not the JVM default.** The JVM's default handler exits 130 without
  unwinding finalizers (native-image is simply killed), which would leak `nerd-font-*.zip` files and `.<Family>-*`
  staging directories. `Main` is a `ZIOAppDefault`, whose runtime installs an interrupt handler that interrupts
  the main fiber, so every `.ensuring`/`acquireRelease`/response-`Scope` finalizer runs, and the process exits 1
  through `Cli` with `install fonts: interrupted` (or `interrupted` before the install). A second SIGINT forces
  the process down with 130, an escape hatch for a cleanup that itself hangs.
- **`AppDependencies` is a case class of functions, not a set of port traits.** Each seam has one call site and the
  tests replace one at a time with a lambda (`deps().copy(listReleases = …)`). `environment` and `colours`
  ride along because the config variable, path expansion and every renderer need them and nothing below the
  composition root may read `sys.env`.
- **`InterruptPhase` is carried on the failure, not decided by the caller.** Only `Application.install` knows an
  interrupt landed inside the engine, so that case gets the `install fonts: ` prefix; catching once in `install`
  and once at the `Cli` boundary keeps both messages exact without threading a phase through every step.
- **The image builder runs with `-Dsun.misc.unsafe.memory.access=allow`.** JDK 25 warns on stderr the first time
  each class calls a deprecated `sun.misc.Unsafe` memory-access method, and `scala.runtime.LazyVals$` does on every
  start. The policy is a `static final` of `sun.misc.Unsafe`, read from the VM's saved startup properties when the
  class initialises — inside the builder, for a native image — so the flag belongs on the builder JVM
  (`-J-D…` in `nativeImageOptions`), not on the binary. A four-line banner on every `--version` would break the
  "error lines are the bare rendered message" contract and every script that checks stderr is empty.
- **`ReleaseUrls` is a value with a base, not an object with a constant.** The CI interrupt smoke needs the
  shipped binary to download from a local stub, and a constant would have forced either a JVM-only test or a
  compile-time flag. The base is threaded as a defaulted parameter (`InstallPlan.of`, `FontInstaller`) so no
  production call site changed, and `ReleaseUrls.apply` strips a trailing slash so an operator-typed override
  cannot produce `//latest`.
- **`NERD_FONTS_INSTALLER_BASE_URL` is a test hook, read once in `AppDependencies.production`.** It is not in
  `--help`, the README or the spec's user-facing surface; it exists for `scripts/ci/interrupt-smoke.sh` and is
  covered by `AppDependenciesSuite` through a dry run, which proves the wiring without a request.
- **Downloads are staged under `$TMPDIR`, then `java.io.tmpdir`.** A non-empty `$TMPDIR` is used first, then the
  JDK's `java.io.tmpdir`; the JDK property is fixed at `/tmp` on Linux, so an operator who redirects temp files
  (or the smoke script, which asserts on the directory) would otherwise see the zip land elsewhere.
- **`--help` lists flags in a fixed order.** With a hand-rolled parser the usage text is written by hand, so the
  order is chosen deliberately, with `--help` last; there is no reflection order to pin down, unlike the
  annotation-bound parser this replaced.
- **The native image passes `-H:+SharedArenaSupport`, and this must never be removed.** zio-http runs on Netty
  4.2.18, whose transport uses `java.lang.foreign.Arena.ofShared` for off-heap buffers. Without
  `-H:+SharedArenaSupport` the GraalVM 25.0.2 build still links, the binary starts, serves its HTTP request
  **correctly and prints the complete, correct output** — and then **never exits**. The failure is not on the
  request path: `UnsupportedFeatureError: Support for Arena.ofShared is not active` is thrown on every
  event-loop thread during Netty's *shutdown* path (`FastThreadLocal.removeAll` →
  `AdaptivePoolingAllocator.onRemoval` → `ArenaImpl.close`), where no ZIO fiber can observe it, so nothing
  catches it and the process hangs holding live event-loop threads. Two consequences that must stay documented:
  (1) a CI check that only greps stdout **passes on a broken binary**, so the network smoke gate must assert the
  **exit code**, not the output; and (2) the hung process **ignores SIGTERM**, so any timeout wrapper must
  escalate to SIGKILL. This is why `--font-names` — the only command that reaches the network without needing a
  config file — is the native-binary network smoke (`scripts/ci/native-network-smoke.sh`): `--version` and
  `--help` never start a Netty event loop, so they exit cleanly even when the HTTP path is completely broken and
  would give a false pass. The flag is documented here with its rationale to stop a future reader "cleaning up"
  an option that appears unused. The image is also built with `--initialize-at-run-time=io.netty` so Netty's
  static initialisers do not run (and capture state) at build time.
- **`nativeImageOptions` pins `-march=compatibility`.** GraalVM 25's AMD64 default is `x86-64-v3`
  (AVX2/BMI2/…), so an unpinned build refuses to start on a pre-2013 CPU or a default-model VM/container —
  before `main` runs, so even `--version` fails. `compatibility` is native-image's baseline-features setting on
  every architecture (it is already AArch64's default, so the arm64 targets are unaffected). A CPU-specific
  `-march` would need a second matrix leg per amd64 target to ship both a fast and a compatible binary; one
  baseline build is the smaller surface.
- **The Linux binaries stay dynamically linked against glibc/libz.** GraalVM's
  static-linking path needs a musl toolchain installed on the runner and produces a separate, less-tested code
  path; the tradeoff accepted here is a documented minimum of glibc ≥ 2.34 (Ubuntu 22.04+, Debian 12+, RHEL/Rocky
  9+) for `linux-amd64`/`linux-arm64`, checked with `ldd`/`objdump -T` against each release build. Revisit if a
  supported-OS report comes in from an older distribution.
- **`release.yml` checks out `inputs.version` for `workflow_dispatch`, `github.sha` otherwise — never the bare
  `github.ref`.** `github.ref` is a moving branch reference that `actions/checkout@v4` re-resolves to that
  branch's *current* tip at checkout time, not the commit that triggered the run. For `workflow_dispatch` this
  meant a re-publish of an old tag was silently building whatever `main` HEAD happened to be instead of the
  tag named in the form. For a plain branch push it is worse and was hit for real on the first push to `main`:
  the build matrix takes ~10 minutes, and a second push landing in that window moved the branch, so the
  `publish` job's checkout picked up the newer commit while `$GITHUB_SHA` (used by `git tag -f latest
  "${GITHUB_SHA}"`) still named the original, now-absent-from-the-shallow-clone one — `git push --force origin
  refs/tags/latest` failed with "nonexistent object". Every checkout in `build` and `publish` pins `ref` to
  `inputs.version` on `workflow_dispatch` and to `github.sha` otherwise; only the `changes` job's checkout is
  unpinned, and only because it diffs `before`/`$GITHUB_SHA` with `fetch-depth: 0`, so which ref it resolves to
  first does not matter — every commit up to whatever it checks out is present either way.
- **`release.yml` fails a tagged build whose `Versions.project` disagrees with the tag.** The binary version is
  a `build.mill` constant, not derived from the tag (a `Task.Input` sourced from the workflow would make local
  builds print `dev`/blank outside CI); a `sed` check of `val project = "…"` against the resolved tag, run once
  per matrix leg before the expensive steps, catches a forgotten version bump instead of shipping a binary whose
  `--version` disagrees with the release it ships in.
- **A pre-release tag never becomes the GitHub "Latest" release.** `gh release create`'s `--latest` defaulted to
  true regardless of the tag shape, so `v*-rc.*` would outrank the last stable release for `scripts/install.sh`'s
  default (unpinned) install path. The publish step now passes `--prerelease --latest=false` whenever the tag
  contains a hyphen (the only shape a pre-release suffix can take under the version regex already enforced in
  "Resolve version") and `--latest` otherwise, applied on both `gh release create` and `gh release edit` so a
  re-run cannot leave a release's flags stale.
- **The temp zip's write and extraction re-opens use `NOFOLLOW_LINKS`.** `createTempZip` creates the file and
  drops the handle; `copyHashing` and `ArchiveExtractor.open` both re-open it by path afterwards. In a shared,
  non-sticky `$TMPDIR` that is a create→reopen symlink-clobber window and a verify→extract TOCTOU window; both
  re-opens refuse to follow a symlink instead of silently writing through or extracting past a swapped file.
- **`JdkProcessRunner.lookPath` never resolves a relative or empty `PATH` entry.** A leading/trailing `:` or a
  `.` on `PATH` would otherwise let a binary in the working directory shadow the real `fc-cache` or `stty`.
  `builder` passes the resolved absolute path as `command(0)` so `ProcessBuilder`'s own PATH search — which does
  not have this guard — never gets a bare name to resolve on its own.
- **`release.yml`'s `changes` job gates the native-image matrix and the publish job on the diff, for branch
  pushes only.** A push to `main` that touches only `docs/**`, `*.md` or similar has nothing to ship, but the
  `latest` pre-release moves and force-pushes its tag on every push regardless; tag pushes and
  `workflow_dispatch` always report `relevant=true` so a real release is never skipped by the path check.
- **`app.run` takes its arguments without a `--` separator.** With the checked-in wrapper (Mill 1.1.7)
  `./mill --no-daemon app.run -- --help` reaches `Main` with an empty argument array (observed: the run
  discovered the developer's own config and installed from it), while `app.run --help` and
  `app.run --config config.example.yaml --dry-run` forward the arguments as typed. `AGENTS.md`,
  `CONTRIBUTING.md` and `checks.yml` spell every invocation without the separator.

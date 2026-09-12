# Architecture

Status: covers every module — `core` (foundation packages and the install engine), `config`, `cli` and `app` — plus the CI scripts and workflows. The behavioural contract is [`SPEC.md`](SPEC.md) (with its
implementation notes); the measured comparison with the Go reference is [`PARITY.md`](PARITY.md). This file
records how the code is shaped and which invariants must never move.

## Module map

```
app -> cli -> { core, config }
config -> core
```

`core` contains the domain, the ports and the install engine. It never imports picocli, fansi or
terminal code. Root package `io.worxbend.nerdfonts`; packages are named after concepts. The edges are `moduleDeps` in `build.mill`.

| Module | Third-party dependencies | Role |
| --- | --- | --- |
| `core` | ox, os-lib, upickle (ujson) | Domain values, ports and adapters, release catalogue, install engine |
| `config` | scala-yaml, upickle | Strict YAML/JSON decoding, defaults, discovery |
| `cli` | picocli, fansi | Process boundary, `Application`, exit codes, event renderer, composition root, generated `BuildInfo` |
| `app` | — | `Main`, SIGINT handler, native-image reflection config; the `NativeImageModule` |

### `core` packages

| Package | Role | Public surface |
| --- | --- | --- |
| (root) | Shared helpers | `Diagnostics.describe(error)` (`private[nerdfonts]`): the `<cause>` text of every message, so no adapter renders a `null` or blank cause; `TerminalSafe.sanitize` (`private[nerdfonts]`): replaces every C0/C1 control character and `DEL` with `?` so upstream text (a family stem, a zip entry name) cannot inject a terminal escape into a rendered line |
| `fonts` | Validated domain values | `FamilyName` (+ `FamilyNameError`), `ReleaseTag`, `ReleaseSelector`, `DestinationPath`, `RefreshFontCache`, `DryRun`, `InstallConfig` (+ `InstallConfig.validated`, `ConfigValidationError`); `GoQuote` (`private[nerdfonts]`) reproduces Go `%q` |
| `environment` | The process environment as a port | `Environment` (`variable`, `property`, `homeDirectory`, `workingDirectory`; `System`, `fixed`), `EnvironmentError`, `PathExpander` (+ `PathError`), `ColourMode` |
| `http` | The one HTTP port and its adapter | `HttpClient` (+ `getString`), `HttpRequest`, `Url`, `ByteLimit`, `Overflow`, `HttpError` (+ `statusLine`), `HttpStatus`, `BoundedInputStream`, `RawResponse` + `ResponseDelivery`, `JdkHttpClient` |
| `releases` | The Nerd Fonts release catalogue | `Release`, `ReleaseCatalogue`, `GitHubReleaseCatalogue`, `ReleaseError`, `ReleaseSelection`, `ReleaseUrls` (a value over one `releases` base; `ReleaseUrls.github` is production), `DownloadUrl`, `Sha256Digest`, `ChecksumManifest` |
| `process` | Subprocesses as a port | `ProcessRunner`, `ProcessSpec` (+ `Stdin`, `Stdout`, `Stderr`), `ProcessResult`, `ExitStatus`, `ProcessError`, `JdkProcessRunner` |
| `install` | The install engine | `InstallRequest`, `InstallPlan` (+ `InstallPlan.of`, `PlannedFamily`), `SizeLimits`, `InstallEvent` + `InstallEventSink`, `FontInstaller`, `ArchiveExtractor` (+ `ArchiveError`, `ArchiveEntryError`, `ExtractedCount`), `DirectorySwap` (+ `SwapError`), `FontCacheRefresher` (+ `FontCacheAvailability`, `FontCacheError`) with `FcCacheRefresher`, `InstallError`, `FamilyInstallError` |

Test-side fakes, public and reusable from every module's tests: `http.InMemoryHttpClient` (routes `Url` →
canned response, records requests, serves bodies through the real `BoundedInputStream`) and
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
| Manifest | `FontInstaller.fetchDigests` | `getString(urls.checksums(selector), limits.manifest, Overflow.Truncate)` under `timeoutEither(30 s)`; any failure is the `ChecksumManifestUnavailable` warning and an empty map |
| Fan-out | `FontInstaller.runFanOut` | One `supervised` scope: `Actor.create(sink)`, `Flow.fromIterable(plan.families).mapParUnordered(min(4, n))(...).runForeach(abortOnFailure)` |
| Family | `FontInstaller.installFamilyNow` | `Started` → temp zip → download + SHA-256 → digest check → staging dir → `ArchiveExtractor.extract` → `DirectorySwap.replace` → `Installed`; the temp zip and staging dir are removed in `finally`; the whole body sits under `timeoutEither(familyDeadline, TimedOut)` |
| Font cache | `FontInstaller.refreshCache` | `refresher.availability` decides between the `FontCacheUnavailable` warning and `RefreshingFontCache` → `refresh(root)` → `FontCacheRefreshed`; `FcCacheRefresher` runs `fc-cache -f <root>` with every stream inherited |
| Caps | `SizeLimits` | `download` 768 MiB, `fontFile` 128 MiB, `archive` 2 GiB, `manifest` 1 MiB, `apiPage` 8 MiB — the Go reference's values, injectable so tests can lower them. Enforced by `ResponseDelivery`/`BoundedInputStream` for bodies and by `ArchiveExtractor` for entries |
| Scratch removal | `Cleanup` (`private[install]`) | Best-effort `removeFile`/`removeTree`, used in `finally` blocks and after a swap's commit point; swallows non-fatal exceptions only, so an interrupt still propagates |

Deadlines and concurrency constants live on the `FontInstaller` companion: `maxConcurrentInstalls = 4`,
`defaultFamilyDeadline = 10 minutes`, `defaultManifestTimeout = 30 seconds`, temp zip name
`nerd-font-*.zip`.


### `config` module — `io.worxbend.nerdfonts.config`

| Type | Role |
| --- | --- |
| `ConfigLoader.load(path): Either[ConfigError, InstallConfig]` | The only route from a file to an `InstallConfig`: read (`NotFound` vs `Unreadable`), decode by extension, default, validate through `InstallConfig.validated` |
| `ConfigDocument` | What the file said: four `Option` fields, text untrimmed. `validated` applies the Go defaults (`latest`, `~/.local/share/fonts/NerdFonts`, refresh off, no families) and delegates to `core` |
| `YamlConfigDecoder`, `JsonConfigDecoder` | Format adapters, `decode(path, text): Either[ConfigError, ConfigDocument]`; each only builds a `ConfigNode` tree and hands it to the shared decoder |
| `ConfigNode`, `ConfigFieldDecoder` (`private[config]`) | The format-neutral tree and the single strict reading of the four keys: unknown key, repeated key and wrong shape are errors named by field |
| `ConfigFormat` (`private[config]`) | `.json` compared case-insensitively on the last extension (Go `filepath.Ext`) selects JSON; everything else, including `.conf` and no extension, is YAML |
| `ConfigLocations.candidates(env): Either[ConfigError, Vector[os.Path]]` | Ordered, de-duplicated candidate list (§4); also owns `appName`, `extensions` and `configVariable` (`NERD_FONTS_INSTALLER_CONFIG`) so `cli` shares the literals |
| `ConfigDiscovery.discover(env, load): Either[ConfigError, Option[DiscoveredConfig]]` | First existing candidate; `DiscoveredConfig(path, config)` is the Go `config.Source` |
| `ConfigError` | `NotFound`, `Unreadable`, `Parse`, `UnknownField`, `WrongType`, `Invalid(ConfigValidationError)`, `NoWorkingDirectory`; `render` is the detail only, the CLI adds `load config <path>: ` / `load discovered config <path>: ` |

Test-side helper: `config.ConfigFiles.write(dir, name, text)` (package-private) writes fixtures into a suite's temp
directory.


### `cli` module — `io.worxbend.nerdfonts.cli`

The Go `main.go` split in two: `Cli` is the process boundary (picocli, `--help`/`--version`, exit codes,
the interrupt catch) and `Application` is the Go `run` after flag parsing, a function from `CliOptions` and
`AppDependencies` to `Either[AppFailure, AppOutcome]` that never sees picocli or the argument array.

| Type | Role |
| --- | --- |
| `Cli.run(args, out, err, deps): Int` (+ the production overload, which passes `AppDependencies.production(Environment.System, Cli.tempDir(env))` — `$TMPDIR` when non-empty, else `java.io.tmpdir`, made absolute) | Builds the root `CommandLine` (`setStopAtPositional`, `Help.Ansi.OFF`, a programmatic `VersionProvider`) and runs it through a custom `IExecutionStrategy`: `CommandLine.executeHelpRequest` (`--help`/`--version`, exit 0) → `Application.run` under `Interruptible` (an escaping `InterruptedException` becomes `AppFailure.Interrupted(BeforeInstall)`) → print the `AppFailure` line → `ExitCode.of`. picocli's own usage errors exit 2 before the strategy runs |
| `RootCommand` (`private[cli]`, `@Command`) | The one class allowed a `var`: picocli binds every option through an annotated setter into a single `OptionDraft`. Both flag spellings per option, an explicit `order` on each (Go's alphabetical listing, help last), `-h/-help/--help` as `usageHelp`, `-version/--version` as `versionHelp`, a hidden `@Parameters(arity = "0..*")` sink, no mixin |
| `CliOptions(explicitConfig: Option[String], mode: CliMode, dryRun)` | The immutable result of parsing; the raw `--config` text survives so `load config <path>` echoes what was typed |
| `AppDependencies` | Function-typed seams (`loadConfig`, `discoverConfig`, `configCandidates`, `listReleases`, `installFonts`, `expandDestination`) plus `environment` and `colours`. `production(env, tempDir)` is the composition root: `JdkHttpClient`, `GitHubReleaseCatalogue`, `FontInstaller(http, tempDir, FcCacheRefresher(JdkProcessRunner(env)), urls = …)`, `ConfigLoader`/`ConfigDiscovery`/`ConfigLocations`, `PathExpander`, `OutputStyle.detect(env, TerminalProbe.isTerminal())`. It is also the only reader of the test hook `NERD_FONTS_INSTALLER_BASE_URL` (`AppDependencies.baseUrlVariable`): a non-blank value becomes `ReleaseUrls(Url(base))`, so the CI interrupt smoke can aim the shipped binary at a local stub; blank or unset keeps `ReleaseUrls.github` |
| `Application` | `run` dispatches on `CliMode`; `printFontNames` (explicit/env/discovered release, else `latest`, never announced), `resolveConfig` (explicit → env → discovered with `Using config <path>` → no-config failure), `selectRelease` (empty listing is `NoReleases` whatever the selector), `install` (expand the destination, build the `InstallRequest`, run the engine through `ConsoleEventRenderer`) |
| `AppOutcome` | `Installed`, `DryRunPrinted`, `FontNamesPrinted` |
| `AppFailure` (+ `InterruptPhase`) | One stderr line per case and the only place the operation prefixes live: `Config(cause, rawPath)` → `load config <raw>: `, with `rawPath` also substituted back in for the absolute path `cause.render` would otherwise show (Go never absolutises `--config`/`$NERD_FONTS_INSTALLER_CONFIG` at all, so both the prefix and the wrapped error echo the same raw text; `Application.resolvePath` treats an empty raw path as `NotFound` outright rather than resolving it to the working directory, matching Go's `open : no such file or directory`); `DiscoveredConfig(cause)` → `load discovered config <path>: ` (bare for `NoWorkingDirectory`; no raw-path substitution, since a discovered candidate has no separate raw spelling), `NoConfig(candidates)` → the two hints, `Release`, `Destination`/`Install` → `install fonts: `, `Interrupted(Install | BeforeInstall)` |
| `ExitCode.of` | `Right` → 0; `NoConfig`, `Release(NotFound | NoReleases)` → 2; every other `Left` → 1 |
| `OutputStyle.detect(env, consoleAttached)` | `NO_COLOR` and `TERM=dumb` always win; otherwise a console or `CLICOLOR_FORCE`/`FORCE_COLOR` (non-empty, not `0`) enables `Ansi` |
| `ConsoleEventRenderer(out, err, colours)` | The only `InstallEventSink` in production: exhaustive match over the eight events, plan lines to stdout, everything else to stderr, lipgloss colours 63/42/214/81/39/219 through fansi in `Ansi` only, no locking |
| `TerminalProbe.isTerminal()` | `Option(System.console()).exists(_.isTerminal)` |
| `Interruptible.run(body)` (`private[cli]`) | The single `try`/`catch` for `InterruptedException`, used by `Cli`'s execution strategy and `Application.install` |
| `BuildInfo` | Generated by `build.mill` (`version`, `commit`, `buildDate`) |

Test-side helper: `cli.Fakes` (package-private) builds an `AppDependencies` whose every seam is a pure function, and
`Fakes.run(deps, args*)` captures both streams and the exit code.

**Exit-code table** (the union of picocli's and `ExitCode.of`):

| Situation | Code | Produced by |
| --- | --- | --- |
| success, dry run, `--font-names` | 0 | `ExitCode.of(Right(_))` |
| `--help`, `--version` | 0 | picocli, inside the execution strategy |
| malformed flag, unknown option, missing option value | 2 | picocli's parameter exception handler |
| no config, unknown release tag, no releases | 2 | `ExitCode.of` |
| everything else: config load/parse/validation, network, filesystem, extraction, checksum, `fc-cache`, interrupt | 1 | `ExitCode.of` |


### `app` module — `io.worxbend.nerdfonts.app`

`Main` only: builds two auto-flushing `PrintWriter`s, installs the SIGINT handler, calls `Cli.run` and exits with
its code. The SIGINT handler (`sun.misc.Signal`) interrupts the main thread on the first signal and
`Runtime.halt(130)`s on the second. `app/resources/META-INF/native-image/io.worxbend/nerd-fonts-installer/reflect-config.json`
lists `RootCommand`; `MainSuite` walks the `cli` class directory on the test class path and fails if any
`@Command`-annotated class is missing from that list. `VersionProvider` is wired programmatically and needs
no entry; the shipped binary's `--help` and `--version` are the runtime proof that the list is complete.

### CI scripts and workflows

| File | Role |
| --- | --- |
| `.github/workflows/checks.yml` | fmt check, scalafix `--check`, compile, tests, then `app.run` from source (`--version`, `--help`, the example dry run) on `ubuntu-24.04` + `macos-15`; a native-image smoke job on linux-amd64 (`--version`, `--help`, `--dry-run`, then the interrupt smoke); actionlint |
| `.github/workflows/release.yml` | Four native images on per-target runners (`ubuntu-24.04`, `ubuntu-24.04-arm`, `macos-15-intel`, `macos-15`), tests on each, tar.gz + sha256 per target, `checksums.txt` + `install.sh` attached, a GitHub Release on `v*` tags (or a `workflow_dispatch` naming an existing tag) and a moving `latest` pre-release refreshed on every push to `main` |
| `.github/dependabot.yml` | Weekly grouped updates for GitHub Actions only; library versions are pinned by hand in `build.mill` |
| `scripts/ci/interrupt-smoke.sh <binary>` | §6.8 end to end against the real binary: a Python stub answers the manifest with 404 and streams an endless `/latest/download/Hack.zip`; the binary runs with `NERD_FONTS_INSTALLER_BASE_URL` and `TMPDIR` pointed at a scratch directory, receives SIGINT one second in, and must exit 1 with `install fonts: interrupted`, leaving no `nerd-font-*.zip`, no `.Hack-*` and no `Hack` directory. Runs under `set -m` so the background binary is not started with SIGINT ignored |
| `scripts/install.sh` | The end-user installer published with every release: detects OS and architecture, downloads the tarball and `checksums.txt` over TLS 1.2+, verifies, installs into `~/.local/bin` |

Application arguments follow `app.run` directly (`./mill --no-daemon app.run --help`): with this
repository's wrapper everything after a `--` separator is dropped, and a bare `app.run` discovers the
developer's own config and installs from it.

## Comparison with the Go implementation

The Go reference is the contract; this code reproduces its exit codes, operation prefixes, config
discovery, output formats, install layout and atomic-replace semantics. [`PARITY.md`](PARITY.md) is the
measured comparison: both binaries were run with identical arguments and environment and their streams
diffed, scenario by scenario, with the verdict per row. The deviations kept on purpose are listed there
and in [`SPEC.md`](SPEC.md) §10; the ones a user can notice are `--help` to stdout with exit 0, parser
wording for a malformed command line and for an unknown YAML key, the removed terminal selection feature, and a
second SIGINT halting with 130. Structurally the two differ where the languages do: Go's `errgroup` is an Ox
`supervised` scope with a `Flow` fan-out and one private exception; Go's `context` cancellation is thread
interruption; Go's `flag` is picocli with both dash spellings declared per option.

## Invariants

1. **The family-name trust boundary.** `FamilyName.parse` is the single path-traversal guard. It rejects the
   empty string, `.`, `..`, any `/` or `\`, NUL, absolute paths and anything whose base name differs from
   itself, with the Go messages verbatim. Every family name touches a path or a URL only as a `FamilyName`.
   `Release.families` is deliberately `Vector[String]`: raw upstream asset stems are display data, and the
   conversion happens exactly once, where a selection becomes an `InstallConfig`.
2. **Every body is capped.** `HttpClient.get` is loan-shaped; bodies are never materialised by the port and
   always flow through `BoundedInputStream`. `ResponseDelivery` is the one implementation of the response
   discipline (non-2xx and oversize `Content-Length` refused before `consume`, body closed on every path,
   overflow overrides `consume`'s result unless `Overflow.Truncate` was requested). The JDK adapter and the
   in-memory fake both delegate to it, so tests exercise the production cap logic. `Overflow.Truncate` is for
   the checksum manifest only.
3. **Checksum policy.** Manifest fetch failure is a warning; a present-but-mismatching digest is fatal. The
   manifest parser skips anything it cannot parse (non-zip lines, unsafe stems, non-64-hex digests, a line cut
   by the read limit); an absent entry means "install unverified", never "fail".
4. **Deadlines belong to callers.** `JdkHttpClient` has only a 30 s connect timeout. `GitHubReleaseCatalogue`
   wraps each page (connect, headers, body, decode) in `timeoutEither(30.seconds)`; the install engine owns its
   own per-family deadline. Never use the throwing `ox.timeout`.
5. **Interrupts are never swallowed.** `InterruptedException` propagates out of every port. The JDK HTTP
   adapter re-asserts the interrupt flag when the JDK reports an interrupt as an `IOException`, so an Ox scope
   still observes the cancellation. `JdkProcessRunner` forcibly destroys a still-running child and waits,
   bounded, for it to be reaped before the interrupt propagates: a captured-stdout read is a classic pipe read
   that ignores `Thread.interrupt`, so it runs under `abandonOnInterrupt`, whose `onAbandon` destroys the
   process and EOFs the pipe.
6. **No shell, ever.** `ProcessSpec.command` is an argv vector handed to `ProcessBuilder`; `Inherit` streams map
   to `Redirect.INHERIT` so `fc-cache` shares the real terminal.
7. **Nothing below the composition root reads `sys.env` / `sys.props`.** `Environment.System` is the only
   place that does; everything else receives an `Environment`. The composition root itself (`Cli.tempDir`,
   `AppDependencies.production`) consults `TMPDIR`, `java.io.tmpdir` and the base-URL hook through that port.
8. **Every user-facing rendering of a non-2xx response goes through `HttpError.Status#statusLine`**, which adds
   the reason phrase `java.net.http` does not expose (`404 Not Found`, unregistered codes render bare).
9. **One loader for every config route.** `--config`, `$NERD_FONTS_INSTALLER_CONFIG` and every discovered
   candidate go through `ConfigLoader.load`; defaults and validation are applied exactly once, in
   `ConfigDocument.validated`, whichever format produced the document. Decoders never see defaults.
10. **Discovery skips only `NotFound`.** A candidate that exists but cannot be read, parsed or validated is
    returned as the error, never skipped (Go: `errors.Is(err, os.ErrNotExist)`).
11. **Strict keys in both formats.** An unknown key, a repeated YAML key or a value of the wrong shape fails the
    load with the field named; nothing is coerced except the documented YAML scalar rules.
12. **Installs are staged, then renamed.** A family is extracted into `<root>/.<Family>-<random>` and becomes
    live only through `DirectorySwap.replace`: remove a stale `<target>.old`, rename the existing target to
    `.old`, rename the staging directory into place. The second rename is the commit point: before it a
    failure restores the previous fonts, after it a cleanup failure is never reported. An existing
    `<root>/<Family>` is therefore untouched by a failed download, a checksum mismatch, a bad archive, a
    deadline or an interrupt.
13. **Per-family paths are disjoint, which is what makes the fan-out safe.** `<root>/<Family>`, its staging
    directory and its `.old` belong to one family; `InstallPlan.of` de-duplicates so two workers never share
    them. At most four families run at once.
14. **The sink is serialised by the engine, never by the sink.** Workers emit through
    `Actor.create(sink)` + `ask`, so `InstallEventSink.emit` is invoked from one thread at a time, in
    submission order, and each worker blocks until its line is written. Events outside the fan-out go to the
    sink on the calling thread. Implementations must not add locking; the test sink is deliberately
    unsynchronised so a broken contract shows up as corruption.
15. **First failure cancels the siblings; finished families stay installed.** A family's `Left` becomes the
    private `FamilyInstallAborted`, raised on the flow's own thread as the result is received; Ox ends the
    fan-out, interrupts the in-flight workers (whose `finally` blocks remove their scratch files) and the
    boundary in `FontInstaller.installAll` catches exactly that type. Nothing else is caught there:
    `InterruptedException` propagates so SIGINT unwinds every `finally` and exits through the CLI.
16. **Deadlines are values.** Each family runs under `timeoutEither(familyDeadline, TimedOut)`; the manifest
    fetch under `timeoutEither(manifestTimeout, …)`. The throwing `ox.timeout` is never used, so a deadline can
    never masquerade as a defect.
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
    Go-style wrappers one level down and do embed an operation prefix for the step *they* represent —
    `list Nerd Fonts releases: `, `locate current directory: `, `open <path>: `/`read <path>: `/`parse <path>: ` —
    so a rendered line can carry two prefixes chained together (e.g. `load config <path>: open <path>: no such
    file or directory`), not just `AppFailure`'s own.
20. **Exit codes come from `ExitCode.of` or from picocli, nowhere else.** `Application` returns values; no step
    chooses a number. The union of the two sources is the exit-code table above.
21. **`Application` never sees the argument array.** picocli types stop at `Cli`; everything below receives
    `CliOptions` and `AppDependencies`, which is what makes every §9 `cli` scenario a test on writers and an `Int`.

## Conventions that reviewers enforce

Braceless Scala 3; explicit return types on public members; opaque types and enums for domain values; `Either`
with a sealed error ADT per concern, each with a `render` that yields the message without any operation
prefix (the caller adds `download <url>: `, `list Nerd Fonts releases: ` and so on); no class-level `var`
(picocli option classes excepted); no `null`, `return`, `while` or mutable collections outside test fakes;
one concern per function; scaladoc on public types explains why, not what.

## Decision log

- **`Url` is unvalidated text.** Every URL is assembled from a constant base and `PathEscape`d segments, so
  validation would only ever catch a defect; `JdkHttpClient` reports a malformed URL as `HttpError.Transport`
  instead of throwing.
- **`HttpError.TooLarge` carries an optional `Content-Length`.** The Go reference prints
  `size <n> bytes exceeds <limit> byte limit` when it rejected on the header and `exceeds <limit> byte limit`
  when the stream overran; one case with the declared size keeps both renderings without a second error type.
- **`ReleaseTag.parse` and `DestinationPath.parse` return `Option`.** Both sources (config, GitHub API) treat a
  blank value as "absent" rather than as an error; `InstallConfig.validated` turns absence into the Go
  `release is required` / `destination is required` messages.
- **`GitHubReleaseCatalogue` owns its page cap default (8 MiB).** `SizeLimits` lives in `install`, which is
  built after this package; the engine passes its own value if it wants a single source of truth.
- **Go `%q` quoting is reproduced (`GoQuote`)** so a family name containing NUL or a backslash renders escaped,
  as the reference does, and never reaches the terminal raw.
- **JSON decoding is strict.** A `tag_name` that is not a string, or `assets` that is not an array, is a
  `ReleaseError.Decode`, matching Go's `encoding/json` rather than silently coercing.
- **Leaf readings are settled by the format adapter.** `ConfigNode.Scalar(asText, asBoolean)` records what a leaf
  may mean: a YAML scalar is text and, when it spells `true/false/yes/no/on/off/y/n` case-insensitively (quoted or
  not), also a boolean; a JSON string is only text, a JSON boolean only a boolean, a JSON number neither. The shared
  `ConfigFieldDecoder` therefore never branches on the format. `1`/`0` are `WrongType` for `refresh_font_cache`.
- **A `null` list entry follows each reference decoder.** yaml.v3 drops a null element of a `[]string`, so the shared
  decoder drops `ConfigNode.Null` entries; `encoding/json` stores the zero string, so the JSON adapter substitutes
  `""` before the tree reaches the decoder and validation reports `font family names cannot be empty`.
- **A YAML stream without a node is the empty document.** §4 defines an empty document as "every key absent"
  (validation then says `at least one font family is required`), although Go reports `parse <path>: EOF`. A blank
  file and a file made only of comments both qualify; scala-yaml reports both as `Expected YAML node, but found:
  StreamEnd`, so `YamlConfigDecoder` recognises them before parsing (a line whose first non-blank character is `#`
  can only be a comment when no scalar was opened earlier, so the check is exact). A leading UTF-8 byte-order mark
  is skipped, as the YAML spec requires and yaml.v3 does; JSON keeps `encoding/json`'s behaviour of rejecting it.
- **Repeated YAML keys are errors.** scala-yaml keeps repeated keys as separate mapping entries, which lets the loader
  reproduce yaml.v3's `mapping key "x" already defined`; ujson keeps the last value, matching `encoding/json`.
- **`multiple json values` is slightly broader than Go.** ujson rejects any trailing content after the first value
  with one clue (`expected whitespace or eof`), which is mapped to Go's message; Go says so only when the trailing
  content is itself valid JSON and otherwise reports the invalid character.
- **`ConfigError` cases carry the path and decoders receive it.** One error ADT for read, decode, validate and
  discovery avoids a second decode-error type that would be remapped case by case in `ConfigLoader`.
- **`Environment` is the only input to discovery.** `ConfigLocations` never reads `sys.env`; `$XDG_CONFIG_HOME` counts
  only when absolute (Go `filepath.IsAbs`), a missing home silently drops the config-home half, a missing working
  directory is `ConfigError.NoWorkingDirectory` rendered `locate current directory: <cause>`.
- **`FamilyInstallAborted` is raised in `runForeach`, not inside the worker.** Ox delivers a worker's exception to
  the flow wrapped in `ChannelClosedException.Error`; raising it as the flow receives each result keeps the
  §6.5 shape (one private type, caught with `.catching` at the boundary, siblings interrupted) without a second
  exception type crossing `supervised`.
- **`ZipInputStream`, as the spec chose, with its two consequences accepted.** A file that is not a zip has no
  entries and is reported as `no font files found` rather than Go's `open font zip`; a corrupt header mid-stream
  is `ArchiveError.Open`. A streamed entry may not declare its size (`-1` when it lives in the data descriptor),
  so the declared checks are skipped for it and the `fontFile + 1` cap plus the running total after the copy
  are the guards; `zip`-built Nerd Fonts archives declare sizes, so production still fails fast.
- **`FamilyInstallError.TempZip` is one case more than the spec lists.** Creating `nerd-font-*.zip` can fail
  before any URL or path is known; Go reports `create temporary zip file: <cause>` and so does this case,
  instead of mis-filing the failure under `Copy`.
- **`FamilyInstallError.render(family)` takes the family.** Only `checksum mismatch for <Family>` needs the name
  and only the `InstallError.Family` wrapper holds it; passing it beats duplicating it in every case.
- **`FontCacheRefresher` has two members.** `availability` and `refresh(root)` are separate so the engine can emit
  `FontCacheUnavailable` versus `RefreshingFontCache` → `FontCacheRefreshed` itself; a single `refresh` returning a
  tri-state would push event ordering into the adapter. `FontCacheError` is typed (`Launch(ProcessError)` /
  `Exit(ExitStatus)`) and renders `exit status 1` like Go's `ExitError`.
- **`ArchiveEntryError.InvalidName`** covers a base name the filesystem cannot represent (a NUL byte in a hostile
  archive); Go would surface it as an `open` failure, here it is named so the message stays readable.
- **`Cleanup` is best effort by design.** Removing the temp zip, the staging directory and a committed swap's
  `.old` swallows non-fatal exceptions only, so an interrupt still propagates while a leftover can never mask
  the real error or fail a successful install.
- **The list window scrolls; the page indicator counts pages.** `ListState` keeps a window offset that follows the
  cursor by the smallest move (one row per `j`/`k`), which reads better than bubbles' page flips; the pagination row
  shows the cursor's page (`●○○`, or `p/N` above ten pages) as a position indicator.
- **`Box` truncates with `…`, lipgloss wraps.** Wrapping would silently add rows and break the height budget that the Go
  tool only holds by luck of its copy lengths; the side-panel note is wrapped explicitly, everything else is cut.
- **`KeyDecoder.Char` is a BMP `Char`.** A supplementary code point (emoji) is dropped rather than split into
  surrogates; family names and filter text never need one.
- **`--help` goes to stdout and exits 0.** Go's `flag` prints usage to stderr and exits 2 only because that is what
  the stdlib does on `-h`; it is not a behaviour anyone scripts against, and `--help | less` is. This is the one
  deliberate departure in the flag surface; `-h`, `-help` and `--help` are all accepted, and there is no `-V`.
- **The exit-code table is the union of picocli's and `ExitCode.of`.** picocli produces 2 for a malformed command
  line and 0 for `--help`/`--version` inside `CommandLine.execute`; every other code is `ExitCode.of` mirroring Go's
  `exitCodeFor` (only `errNoConfig`, `ErrNoReleases` and `ReleaseNotFoundError` are 2). The constants are picocli's
  `ExitCode.OK/SOFTWARE/USAGE` so the two sources cannot drift.
- **`RootCommand` holds the codebase's one class-level `var`.** picocli binds options by invoking annotated setters
  on an instance it reads reflectively, so some mutable slot is unavoidable. It is a single `private var` holding an
  immutable `OptionDraft` that setters `copy`, the class exposes only `options()`, and nothing else in the
  codebase may hold a `var` field (scalafix `DisableSyntax.noVars`, suppressed on exactly this class).
- **A custom `IExecutionStrategy` instead of `Callable`.** The strategy calls `executeHelpRequest`, then the
  application. It is also the only frame that can catch an interrupt: whatever the strategy throws,
  `CommandLine.execute` catches, prints as a stack trace and turns into 1, so the catch cannot sit around
  `execute`.
- **SIGINT is an interrupt of the main thread, not the JVM default.** The JVM's default handler exits 130 without
  unwinding `finally` (native-image is simply killed), which would leak `nerd-font-*.zip` files and `.<Family>-*`
  staging directories. `Main` installs `sun.misc.Signal.handle(INT)` → `mainThread.interrupt()`, so Ox scopes
  unwind, every cleanup runs and the process exits 1 through `Cli` with
  `install fonts: interrupted` (or `interrupted` before the install) — Go's cancelled context. A second SIGINT
  `Runtime.halt(130)`s: the one escape hatch Go does not offer, for a cleanup that itself hangs. The interrupt
  flag is left cleared after the catch; nothing blocking runs after it.
- **`AppDependencies` is a case class of functions, not a set of port traits.** Each seam has one call site and the
  tests replace one at a time with a lambda (`deps().copy(listReleases = …)`). `environment` and `colours`
  ride along because the config variable, path expansion and every renderer need them and nothing below the
  composition root may read `sys.env`.
- **`InterruptPhase` is carried on the failure, not decided by the caller.** Only `Application.install` knows an
  interrupt landed inside the engine, and Go reports that case with the `install fonts: ` prefix; catching once in
  `install` and once in the strategy keeps both messages exact without threading a phase through every step.
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
- **Downloads are staged under `$TMPDIR`, then `java.io.tmpdir`.** Go's `os.CreateTemp("", …)` honours the
  variable; the JDK property is fixed at `/tmp` on Linux, so an operator who redirects temp files (or the smoke
  script, which asserts on the directory) would otherwise see the zip land elsewhere.
- **Every picocli option carries an explicit `order`.** picocli lists setter-bound options in reflection order,
  which the JVM does not define; the first native build listed `--font-names` before `--config`. The order is
  Go's (`flag` sorts alphabetically) with `--help` last, because Go does not list it at all.
- **`nativeImageOptions` pins `-march=compatibility`.** GraalVM 25's AMD64 default is `x86-64-v3`
  (AVX2/BMI2/…), so an unpinned build refuses to start on a pre-2013 CPU or a default-model VM/container —
  before `main` runs, so even `--version` fails. `compatibility` is native-image's baseline-features setting on
  every architecture (it is already AArch64's default, so the arm64 targets are unaffected) and matches the Go
  reference's `GOAMD64=v1`. A CPU-specific `-march` would need a second matrix leg per amd64 target to ship both
  a fast and a compatible binary; one baseline build is the smaller surface.
- **The Linux binaries stay dynamically linked against glibc/libz, unlike the static Go reference.** GraalVM's
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
- **`JdkProcessRunner.lookPath` never resolves a relative or empty `PATH` entry.** Go's `exec.LookPath` has
  refused such a match since 1.19 (`exec.ErrDot`) because a leading/trailing `:` or a `.` on `PATH` would
  otherwise let a binary in the working directory shadow the real `fc-cache` or `stty`. `builder` passes the
  resolved absolute path as `command(0)` so `ProcessBuilder`'s own PATH search — which does not have this
  guard — never gets a bare name to resolve on its own.
- **`release.yml`'s `changes` job gates the native-image matrix and the publish job on the diff, for branch
  pushes only.** A push to `main` that touches only `docs/**`, `*.md` or similar has nothing to ship, but the
  `latest` pre-release moves and force-pushes its tag on every push regardless; tag pushes and
  `workflow_dispatch` always report `relevant=true` so a real release is never skipped by the path check.
- **`app.run` takes its arguments without a `--` separator.** With the checked-in wrapper (Mill 1.1.7)
  `./mill --no-daemon app.run -- --help` reaches `Main` with an empty argument array (observed: the run
  discovered the developer's own config and installed from it), while `app.run --help` and
  `app.run --config config.example.yaml --dry-run` forward the arguments as typed. `AGENTS.md`,
  `CONTRIBUTING.md` and `checks.yml` spell every invocation without the separator.

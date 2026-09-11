# Architecture

Status: covers `core` (foundation packages and the install engine), the `config` module and the `picker` module.
Agents building `cli` and `app` extend this document in the same change that adds their module. The behavioural contract is
[`SPEC.md`](SPEC.md); this file records how the code is shaped and which invariants must never move.

## Module map

```
app -> cli -> { core, config, picker }
config -> core
picker -> core
```

`core` contains the domain, the ports and the install engine. It never imports picocli, fansi or
terminal code. Root package `io.worxbend.nerdfonts`; packages are named after concepts.

### `core` packages

| Package | Role | Public surface |
| --- | --- | --- |
| `fonts` | Validated domain values | `FamilyName` (+ `FamilyNameError`), `ReleaseTag`, `ReleaseSelector`, `DestinationPath`, `RefreshFontCache`, `DryRun`, `InstallConfig` (+ `InstallConfig.validated`, `ConfigValidationError`) |
| `environment` | The process environment as a port | `Environment` (`System`, `fixed`), `EnvironmentError`, `PathExpander` (+ `PathError`), `ColourMode` |
| `http` | The one HTTP port and its adapter | `HttpClient` (+ `getString`), `HttpRequest`, `Url`, `ByteLimit`, `Overflow`, `HttpError` (+ `statusLine`), `HttpStatus`, `BoundedInputStream`, `RawResponse` + `ResponseDelivery`, `JdkHttpClient` |
| `releases` | The Nerd Fonts release catalogue | `Release`, `ReleaseCatalogue`, `GitHubReleaseCatalogue`, `ReleaseError`, `ReleaseSelection`, `ReleaseUrls`, `DownloadUrl`, `Sha256Digest`, `ChecksumManifest` |
| `process` | Subprocesses as a port | `ProcessRunner`, `ProcessSpec` (+ `Stdin`, `Stdout`, `Stderr`), `ProcessResult`, `ExitStatus`, `ProcessError`, `JdkProcessRunner` |
| `install` | The install engine | `InstallRequest`, `InstallPlan` (+ `InstallPlan.of`, `PlannedFamily`), `SizeLimits`, `InstallEvent` + `InstallEventSink`, `FontInstaller`, `ArchiveExtractor` (+ `ArchiveError`, `ArchiveEntryError`, `ExtractedCount`), `DirectorySwap` (+ `SwapError`), `FontCacheRefresher` (+ `FontCacheAvailability`, `FontCacheError`) with `FcCacheRefresher`, `InstallError`, `FamilyInstallError` |

Test-side fakes, public and reusable from every module's tests: `http.InMemoryHttpClient` (routes `Url` →
canned response, records requests, serves bodies through the real `BoundedInputStream`) and
`process.FakeProcessRunner` (prefix-matched scripts, records calls, fixed `lookPath` table).
The `install` suites add package-private helpers only (`FontZips` builds zips in memory, `RecordingSink` is a
deliberately unsynchronised sink, `GatedHttpClient` holds chosen requests behind a latch); other modules drive
the engine through `InstallEventSink` and the two public fakes.

### The install engine — `io.worxbend.nerdfonts.install`

`FontInstaller(http, tempDir, refresher, limits, familyDeadline, manifestTimeout)` is the only class with
behaviour; everything else is a value, a port or a pure step.

| Step | Owner | Shape |
| --- | --- | --- |
| Plan | `InstallPlan.of(request)` | Pure: de-duplicate families (first occurrence wins), compute `ReleaseUrls.download(selector, family)` and `root / family` per family. `FontInstaller.plan` returns it; `install` executes exactly it |
| Dry run | `FontInstaller.describe` | `WouldInstall` per planned family, then `WouldRefreshCache(root)`; no port is touched |
| Root | `FontInstaller.createRoot` | `os.makeDir.all(root)`; failure is `InstallError.Destination` and nothing else runs |
| Manifest | `FontInstaller.fetchDigests` | `getString(checksums, limits.manifest, Overflow.Truncate)` under `timeoutEither(30 s)`; any failure is the `ChecksumManifestUnavailable` warning and an empty map |
| Fan-out | `FontInstaller.runFanOut` | One `supervised` scope: `Actor.create(sink)`, `Flow.fromIterable(plan.families).mapParUnordered(min(4, n))(...).runForeach(abortOnFailure)` |
| Family | `FontInstaller.installFamilyNow` | `Started` → temp zip → download + SHA-256 → digest check → staging dir → `ArchiveExtractor.extract` → `DirectorySwap.replace` → `Installed`; the temp zip and staging dir are removed in `finally`; the whole body sits under `timeoutEither(familyDeadline, TimedOut)` |
| Font cache | `FontInstaller.refreshCache` | `refresher.availability` decides between the `FontCacheUnavailable` warning and `RefreshingFontCache` → `refresh(root)` → `FontCacheRefreshed`; `FcCacheRefresher` runs `fc-cache -f <root>` with every stream inherited |


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


### `picker` module — `io.worxbend.nerdfonts.picker`

The Go Bubble Tea program rebuilt as three separable layers. The **model** is an immutable case class with a
pure `update`; the **view** is a pure function from model and `ColourMode` to a `Frame`; the **terminal** is a
port with one production adapter. Only `PickerSession` touches all three, and it holds no state of its own.

| Type | Role |
| --- | --- |
| `PickerModel.initial(releases, destination, refreshFontCache, iconMode, viewport)` / `update(key)` / `resized(viewport)` / `outcome` | The state machine. `PickerStep` is `ChooseRelease`, `ChooseFamilies(release, families: ListState, selected)`, `Done(release, selected)` or `Cancelled`; each step carries exactly its own state, so a family list cannot exist without the release it came from. The release list lives on the model so going back returns to the same cursor and filter |
| `PickerKey` | The decoded key vocabulary (`Up … CtrlK`, `Char(c)`); the model never sees bytes |
| `ListState` (+ `ListItem`, `FilterState`, `FilteredItem`) | bubbles' `list.Model` reduced to pure operations: move/page/first/last, `openFilter`/`typeChar`/`eraseChar`/`applyFilter`, `withItems`, and a scrolling window (`windowStart`/`visibleItems`/`scrolled(pageSize)`) that follows the cursor with the smallest move. `handle(key, pageSize)` is the list's half of the precedence table |
| `FuzzyMatcher` (`private[picker]`) | Case-insensitive subsequence match over `title + " " + description + " " + value`, ranked by first position then span, stable for ties; positions are kept so the view can underline them |
| `PickerOutcome.of(release, selected, destination, refreshFontCache)` | `Cancelled` when nothing is selected, else every stem through `FamilyName.parse` (sorted, first failure wins) → `Selected(InstallConfig)` or `Rejected(ConfigValidationError.InvalidFamily)` |
| `PickerView.render(model, colours): Frame` | The Go `View()`: banner box, list panel, side panel (wide layouts, only when it fits), help footer, and the done screen. `Layout` (`private[picker]`) owns the budget constants; `ListView` renders a `ListState` into exactly `listHeight` rows; `Box` draws a rounded, padded box of an exact size; `TextWidth` is the cell arithmetic (`displayWidth`, ANSI-aware `truncate`, `fit`, `wrap`) |
| `Palette` (+ `Colour`, `Styles`) | The neon palette, `brandRamp`, `gradientText`/`gradientRule`/`spread`/`statLine`/`progressBar`/`percentage`; every helper takes the `ColourMode` and returns bare text in `Plain` |
| `IconMode` (+ `parse`, `IconModeError`), `IconSet` (+ `forMode`, `iconForFamily`, `logo`), `FamilyHint.of` | The Go icon tables verbatim (as `\u` escapes so the private-use glyphs survive tooling); `auto` resolves to the Unicode set |
| `Terminal.withRawMode(body: RawTerminal => A): Either[TerminalError, A]`, `RawTerminal` (`size()`, `readKey()`, `write(frame)`) | The loan-shaped port; raw mode, alternate screen and hidden cursor exist only inside the loan |
| `SttyTerminal(processRunner, escapeTimeout = 50 ms, streams = TerminalStreams.process)` | The adapter: `stty -g` / `stty raw -echo` / `stty <saved>` through `ProcessRunner` with `Stdin.FromFile(/dev/tty)` and `Stdout.Capture`, never a shell; `stty size` per frame with an 80×24 fallback; frames as `ESC[H` + lines joined by `\r\n` (each followed by `ESC[K`) + `ESC[J`, one flushed write |
| `KeyDecoder(input, escapeTimeout)` | Bytes → `PickerKey` per the §7 table; the byte after `ESC` is read under `timeoutOption`; unknown CSI/SS3 sequences and supplementary code points are consumed and dropped |
| `StdinSource.stream` | The one `abandonOnInterruptReads(System.in)` in the process |
| `PickerSession.run(releases, icons, colours, terminal): Either[PickerError, PickerOutcome]` | render → read → update as a tail-recursive loop; `PickerError.NoReleases` before the terminal is touched, `PickerError.Terminal` when raw mode fails; end of input is a cancellation |
| `ReleaseLoadingSpinner.around(stderr, colours)(load)` | The stderr spinner block: a daemon ticker fork inside a `supervised` scope whose body is `load()`, so the ticker is cancelled and joined before the final line; the line is redrawn with `\r` and space padding, never `ESC[K` |

Test-side helper, public and reusable from `cli` tests: `picker.ScriptedTerminal(keys, viewport, rawMode)` feeds
a key script, records every `Frame` and counts raw-mode entries and exits.

**Key precedence** (independent of filter focus, because the model consumes its keys before the list sees them):

1. `q`, `Ctrl-C` cancel on both steps; `Esc` goes back on the families step and cancels on the release step. `Esc`
   never clears a filter and `q` cannot be typed into one.
2. Step keys: release — `Enter` chooses the highlighted (filtered) release; families — `Enter` finishes (no-op with
   nothing selected), `Space` toggles, `a` selects all / clears all, `b` goes back. None can be typed into the family
   filter; on the release step `Space`, `a` and `b` are ordinary characters.
3. The list: while browsing `Up`/`k`, `Down`/`j`, `PgUp`/`PgDn`, `Home`/`g`, `End`/`G`, `/` (opens the input with the
   applied text, cursor reset); while editing, printable characters and `Backspace` re-filter live, `Up`/`Down`/
   `Tab`/`Shift-Tab`/`Ctrl-K`/`Ctrl-J` apply (an empty or match-less pattern clears instead), paging keys are dead,
   `Enter` never applies.

**Height budget invariant.** `Layout.listHeight = max(9, safeHeight − chrome)` with `chrome = 16` (full banner) or
`14` (compact, `safeHeight < 26`), floors 48×24, `bodyWidth ≤ 132`, side panel 34 wide from 104 columns. The banner
box is 9 (or 7) rows, the list panel `listHeight + 4`, plus two separators and the footer — so a rendered frame is
exactly `safeHeight` rows. `Box` truncates instead of wrapping (every wrapped row would break the budget); the side
panel is the only wrapped text and is dropped when taller than the list panel. The size matrix test
(40×10 … 200×60, both steps) asserts height `== safeHeight` and every line `≤ safeWidth` in both colour modes.

**The CRLF rule.** `stty raw` clears `opost`/`onlcr`, so the terminal no longer expands `\n`. Every line break the
adapter emits while in raw mode is an explicit `\r\n`, produced in one place (`SttyTerminal.framePaint`); a test
scans every byte written for a `\n` without a preceding `\r`.

## Invariants

1. **The family-name trust boundary.** `FamilyName.parse` is the single path-traversal guard. It rejects the
   empty string, `.`, `..`, any `/` or `\`, NUL, absolute paths and anything whose base name differs from
   itself, with the Go messages verbatim. Every family name touches a path or a URL only as a `FamilyName`.
   `Release.families` is deliberately `Vector[String]`: raw upstream asset stems are display data, and the
   conversion happens exactly once, where a selection becomes an `InstallConfig` (config loader, picker).
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
   still observes the cancellation. `JdkProcessRunner` destroys a still-running child before propagating.
6. **No shell, ever.** `ProcessSpec.command` is an argv vector handed to `ProcessBuilder`; `Inherit` streams map
   to `Redirect.INHERIT` so `fc-cache` shares the real terminal.
7. **Nothing below the composition root reads `sys.env` / `sys.props`.** `Environment.System` is the only
   place that does; everything else receives an `Environment`.
8. **Every user-facing rendering of a non-2xx response goes through `HttpError.Status#statusLine`**, which adds
   the reason phrase `java.net.http` does not expose (`404 Not Found`, unregistered codes render bare).
9. **One loader for every config route.** `--config`, `$NERD_FONTS_INSTALLER_CONFIG` and every discovered
   candidate go through `ConfigLoader.load`; defaults and validation are applied exactly once, in
   `ConfigDocument.validated`, whichever format produced the document. Decoders never see defaults.
10. **Discovery skips only `NotFound`.** A candidate that exists but cannot be read, parsed or validated is
    returned as the error, never skipped and never a reason to start the picker (Go: `errors.Is(err, os.ErrNotExist)`).
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

12. **Picker output crosses the `FamilyName` boundary exactly once**, in `PickerOutcome.of`. Stems are display
    data until then; an unsafe stem is `Rejected`, never a path.
13. **The picker model is pure and the terminal is a loan.** `PickerModel.update` is a total function of model and
    key; `Terminal.withRawMode` restores `stty` settings, the alternate screen and the cursor in a `finally`, so an
    interrupt unwinding through a read leaves the terminal usable. Nothing in `picker` reads `System.in` except
    `StdinSource`, once.
14. **Frames fit.** `PickerView.render` never yields more than `safeHeight` rows or a line wider than `safeWidth`;
    any new banner row or panel must be paid for in `Layout`'s chrome constants.
15. **Plain means plain.** With `ColourMode.Plain` no picker or spinner output contains `ESC[`; the alternate-screen,
    cursor and clear sequences are the terminal adapter's, emitted in raw mode only.

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
- **`PickerStep.Cancelled` is a fourth step, not a flag.** The spec lists three steps; a terminal `Cancelled` case lets
  `update` ignore every key after the end without a separate boolean and makes `outcome` a plain `match`.
- **The list window scrolls; the page indicator counts pages.** `ListState` keeps a window offset that follows the
  cursor by the smallest move (one row per `j`/`k`), which reads better than bubbles' page flips; the pagination row
  shows the cursor's page (`●○○`, or `p/N` above ten pages) as a position indicator.
- **`Box` truncates with `…`, lipgloss wraps.** Wrapping would silently add rows and break the height budget that the Go
  tool only holds by luck of its copy lengths; the side-panel note is wrapped explicitly, everything else is cut.
- **`KeyDecoder.Char` is a BMP `Char`.** A supplementary code point (emoji) is dropped rather than split into
  surrogates; family names and filter text never need one.
- **Unknown escape sequences are consumed whole.** A modified arrow (`ESC [ 1 ; 5 A`) is read to its final byte and
  dropped, so its parameter bytes cannot leak into the filter as text.
- **The spinner pads with spaces instead of `ESC[K`.** Keeps `Plain` output free of control sequences and makes the
  final line testable as text.
- **`picker.test` depends on `core.test`** (build.mill) so `SttyTerminal` is tested against the shared
  `FakeProcessRunner` rather than a second fake.
- **Go's `h/l/f/d/u` paging aliases are not bound** (§7 deviation, kept): `h`/`l` would collide with typing and the
  remaining aliases add nothing over `PgUp`/`PgDn`.

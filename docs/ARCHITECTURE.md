# Architecture

Status: covers the foundation half of `core` and the `config` module. Agents building `install`, `picker`,
`cli` and `app` extend this document in the same change that adds their module. The behavioural contract is
[`SPEC.md`](SPEC.md); this file records how the code is shaped and which invariants must never move.

## Module map

```
app -> cli -> { core, config, picker }
config -> core
picker -> core
```

`core` contains the domain, the ports and (later) the install engine. It never imports picocli, fansi or
terminal code. Root package `io.worxbend.nerdfonts`; packages are named after concepts.

### `core` packages built so far

| Package | Role | Public surface |
| --- | --- | --- |
| `fonts` | Validated domain values | `FamilyName` (+ `FamilyNameError`), `ReleaseTag`, `ReleaseSelector`, `DestinationPath`, `RefreshFontCache`, `DryRun`, `InstallConfig` (+ `InstallConfig.validated`, `ConfigValidationError`) |
| `environment` | The process environment as a port | `Environment` (`System`, `fixed`), `EnvironmentError`, `PathExpander` (+ `PathError`), `ColourMode` |
| `http` | The one HTTP port and its adapter | `HttpClient` (+ `getString`), `HttpRequest`, `Url`, `ByteLimit`, `Overflow`, `HttpError` (+ `statusLine`), `HttpStatus`, `BoundedInputStream`, `RawResponse` + `ResponseDelivery`, `JdkHttpClient` |
| `releases` | The Nerd Fonts release catalogue | `Release`, `ReleaseCatalogue`, `GitHubReleaseCatalogue`, `ReleaseError`, `ReleaseSelection`, `ReleaseUrls`, `DownloadUrl`, `Sha256Digest`, `ChecksumManifest` |
| `process` | Subprocesses as a port | `ProcessRunner`, `ProcessSpec` (+ `Stdin`, `Stdout`, `Stderr`), `ProcessResult`, `ExitStatus`, `ProcessError`, `JdkProcessRunner` |

Test-side fakes, public and reusable from every module's tests: `http.InMemoryHttpClient` (routes `Url` →
canned response, records requests, serves bodies through the real `BoundedInputStream`) and
`process.FakeProcessRunner` (prefix-matched scripts, records calls, fixed `lookPath` table).


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
- **Empty YAML is an empty document; a comment-only file is a parse error.** §4 defines an empty document as "every
  key absent" (validation then says `at least one font family is required`), although Go reports `EOF`. scala-yaml
  cannot tell a comment-only stream from a syntax error, so that case stays an error, as it is in Go.
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

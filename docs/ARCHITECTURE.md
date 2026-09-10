# Architecture

Status: first version, covering the foundation half of `core`. Agents building `install`, `config`, `picker`,
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

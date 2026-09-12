# Contributing

Thanks for helping. This file is the short version; [`AGENTS.md`](AGENTS.md) is the operating guide that
humans and coding agents share, [`docs/SPEC.md`](docs/SPEC.md) is the behavioural contract, and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) is the module map with the invariants and the decision log.
Parity with the Go reference ([`worxbend/nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer))
is a requirement; [`docs/PARITY.md`](docs/PARITY.md) records the measured comparison.

## Prerequisites

- `git` and the `./mill` wrapper in the repository. Nothing else: the wrapper downloads Mill 1.1.9
  (`.mill-version`) and Mill fetches the toolchain, GraalVM Community for JDK 25 (`Versions.graalvm` in
  `build.mill`). No system JDK, no `GRAALVM_HOME`, no `JAVA_HOME`.
- For the native binary only, `native-image` links with the platform toolchain: a C compiler, libc headers
  and zlib on Linux (`gcc`, `glibc-devel`/`libc6-dev`, `zlib1g-dev` or equivalents), Xcode Command Line Tools
  on macOS.
- `python3` to run `scripts/ci/interrupt-smoke.sh` locally.

The first build downloads a few hundred megabytes; CI caches `~/.cache/coursier`, `~/.cache/mill` and
`~/.mill` for the same reason.

## The gate

Every change must pass all of these before it is committed; CI (`.github/workflows/checks.yml`) runs the
same commands on `ubuntu-24.04` and `macos-15`.

| Check | Command |
| --- | --- |
| Format | `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` (fix with `…/reformatAll`) |
| Scalafix | `./mill --no-daemon __.fix --check` (fix with `__.fix`) |
| Compile, warnings are errors | `./mill --no-daemon __.compile` |
| All tests | `./mill --no-daemon __.test` |
| One module's tests | `./mill --no-daemon core.test` (also `config`, `cli`, `app`) |
| Smoke from source | `./mill --no-daemon app.run --config config.example.yaml --dry-run` |

Pass the application's arguments directly after `app.run`. With this repository's `./mill` wrapper
(Mill 1.1.9) everything after a `--` separator is dropped, so `app.run -- --help` runs the tool with no
arguments, which discovers your config and installs fonts.

Definition of done, from `AGENTS.md`: compiles with `-Werror`, all tests pass, formatted, scalafix clean, and
`docs/ARCHITECTURE.md` updated in the same change whenever a boundary, contract or invariant moved. A
deviation from `docs/SPEC.md` is listed in the commit or PR description and reflected back into the spec's
§11 implementation notes.

## Module layout

```
app -> cli -> { core, config }
config -> core
```

| Module | Contents |
| --- | --- |
| `core` | Domain values (`fonts`), the ports (`http`, `process`, `environment`), the release catalogue (`releases`) and the install engine (`install`). Never imports CLI or terminal code. |
| `config` | Lenient YAML/JSON/HOCON decoding into `InstallConfig` via zio-config, defaults, discovery. |
| `cli` | The argument parser, `Application` (the run after flag parsing), exit codes, the event renderer, the composition root. |
| `app` | `Main` (a `ZIOAppDefault`), the SIGINT handler, the native-image reflection config. |

Sources live in `<module>/src`, tests in `<module>/test/src`, both under the package root
`io.worxbend.nerdfonts`. `build.mill` is the whole build; there are no plugins beyond scalafix.

## Coding rules

- ZIO-native Scala 3: braceless syntax, explicit return types on public members, opaque types and enums
  for domain values (`FamilyName`, `ReleaseTag`, `ByteLimit`, `DryRun`, …), never raw `String`/`Boolean` in
  a domain position.
- Recoverable failures live in ZIO's typed error channel with one sealed error ADT per concern; each ADT
  has a `render` that yields the message without an operation prefix, which the caller adds. Exceptions
  cross a boundary only as defects; every `ZIO.attempt` maps its throwable into the ADT.
- No class-level `var`, no `null`, no `return`, no `while` (scalafix `DisableSyntax` enforces it), and no
  mutable state outside `Ref`.
- Side effects live behind small ports (`HttpClient`, `ProcessRunner`, `Environment`,
  `FontCacheRefresher`, `InstallEventSink`); concurrency is ZIO (fibers, `Scope`, `ZIO.timeout`), never
  Futures and never blocking waits off the blocking pool. Nothing below the composition root reads
  `sys.env` or `sys.props`.
- The security invariants are not up for negotiation: every family name passes `FamilyName.parse` before
  touching a path or URL; downloads and extraction are byte-capped; a checksum mismatch is fatal; installs
  are staged then renamed. See [`docs/SECURITY.md`](docs/SECURITY.md).

## Tests

zio-test with `zio-test-magnolia` for property tests; one scenario per test, named as a sentence
(`test("rejects a family name containing a slash")`). Tests never leave the machine: the zio-http adapter
is tested against a loopback server, everything else against fakes.

| Location | Fake | Used by |
| --- | --- | --- |
| `core/test` | `http.InMemoryHttpClient` (routes `Url` → canned response, records requests, serves bodies through the real byte cap) | every module |
| `core/test` | `process.FakeProcessRunner` (prefix-matched scripts, records calls, fixed `lookPath` table) | `core`, `cli` |
| `core/test` (`install`, package-private) | `FontZips` builds zips in memory, `RecordingSink` records `InstallEventSink` events, `GatedHttpClient` holds chosen requests behind a latch for interrupt and deadline tests | `FontInstallerSuite` |
| `config/test` | `ConfigFiles.write(dir, name, text)` writes fixtures into a suite's temp directory | `config` |
| `cli/test` | `Fakes` builds an `AppDependencies` whose every seam is a pure function and `Fakes.run(deps, args*)` captures both streams and the exit code | `cli` |

When you add a port, add its fake next to the port's tests and make it public if another module will need
it. When you change a user-facing message, update the golden test in `cli/test` and check the wording
against the Go reference; `docs/PARITY.md` lists the scenarios that were diffed byte for byte.

## Commits and pull requests

- Conventional Commits (`feat(core): …`, `fix(cli): …`, `docs: …`, `build: …`, `ci: …`, `test: …`), small and
  individually green.
- Branch off `main`. Do not push to `main` directly; open a pull request and fill in the template.
- If your harness or tooling provides a `Co-Authored-By` trailer, keep it.
- Update the `Unreleased` section of [`CHANGELOG.md`](CHANGELOG.md) for anything a user would notice.

## Building the native binary locally

```bash
./mill --no-daemon show app.nativeImage
# prints a ref ending in out/app/nativeImage.dest/native-executable

out/app/nativeImage.dest/native-executable --version
out/app/nativeImage.dest/native-executable --config config.example.yaml --dry-run
scripts/ci/interrupt-smoke.sh out/app/nativeImage.dest/native-executable
```

`--version` prints `nerd-fonts-installer <version> (<commit>, <date>)`; the commit comes from
`git rev-parse --short=12 HEAD`, the date from `NERD_FONTS_INSTALLER_BUILD_DATE` (else `unknown`), both
generated into `cli.BuildInfo` by `build.mill`. The image is built with `--no-fallback`, so a class that
needs reflection must be listed in
`app/resources/META-INF/native-image/io.worxbend/nerd-fonts-installer/reflect-config.json`; the hand-rolled
parser uses no reflection, so `MainSuite` asserts that file stays empty. `./mill --no-daemon app.writeAssembly`
writes a runnable JVM jar to `dist/nerd-fonts-installer.jar` for local convenience; nothing ships as a jar.

## Cutting a release

1. Bump `Versions.project` in `build.mill`, move the `Unreleased` entries in `CHANGELOG.md` under the new
   version, and land that on `main` through a pull request.
2. Tag the merge commit `vX.Y.Z` (an optional `-prerelease` suffix is accepted) and push the tag.
3. `.github/workflows/release.yml` builds four native images on per-target runners (`linux-amd64` on
   `ubuntu-24.04`, `linux-arm64` on `ubuntu-24.04-arm`, `macos-amd64` on `macos-15-intel`, `macos-arm64` on
   `macos-15`), runs the tests on each, packages `nerd-fonts-installer_vX.Y.Z_<target>.tar.gz` with `LICENSE`,
   `config.example.yaml` and `README.md` (when present), and publishes a GitHub Release with `checksums.txt`
   and `scripts/install.sh` attached, using generated notes.
4. Every push to `main` also refreshes the moving `latest` pre-release with the same asset names. A
   `workflow_dispatch` run with an existing tag re-publishes that tag's assets.

Nothing else is published: there is no package registry, no snap and no website.

<div align="center">

<img src="assets/logo.svg" alt="nerd-fonts-installer-scala logo" width="112" height="112" />

# nerd-fonts-installer-scala

### Install Nerd Fonts from one YAML file — a single native binary, no JVM required.

[![Release](https://img.shields.io/github/v/release/worxbend/nerd-fonts-installer-scala?sort=semver&label=release&color=C75CFF)](https://github.com/worxbend/nerd-fonts-installer-scala/releases)
[![Checks](https://img.shields.io/github/actions/workflow/status/worxbend/nerd-fonts-installer-scala/checks.yml?branch=main&label=checks&color=5BF0B8)](https://github.com/worxbend/nerd-fonts-installer-scala/actions/workflows/checks.yml)
[![License: MIT](https://img.shields.io/github/license/worxbend/nerd-fonts-installer-scala?label=license&color=5BA8FF)](LICENSE)
[![Scala 3](https://img.shields.io/badge/Scala-3.8-DC322F?logo=scala&logoColor=white)](https://scala-lang.org)
[![GraalVM native image](https://img.shields.io/badge/GraalVM-native--image-46E5E0)](https://www.graalvm.org/latest/reference-manual/native-image/)
[![Platforms](https://img.shields.io/badge/platform-linux%20%7C%20macOS-8A7CFF)](#quick-start)

[Quick start](#quick-start) &nbsp;•&nbsp;
[Configuration](#configuration) &nbsp;•&nbsp;
[Command reference](#command-reference) &nbsp;•&nbsp;
[Troubleshooting](#troubleshooting) &nbsp;•&nbsp;
[Security](#security-in-brief)

<img src="assets/screenshots/cli-dry-run.svg" alt="nerd-fonts-installer --dry-run: four planned installs with their download URL and destination, plus a font-cache line — nothing touched yet" width="820" />

</div>

---

## Why

Installing a Nerd Font by hand is the same eight steps every time: find the release page, pick the right
archive, download it, unzip it, fish out only the font files, move them into a font directory, refresh the
font cache — then do it again for every family, on every machine.

<table>
<tr><th align="left">😩 By hand, every time</th><th align="left">✨ With this</th></tr>
<tr valign="top"><td>

1. Open the Nerd Fonts release page
2. Find the right archive
3. Download it, unzip it
4. Fish out only the font files
5. Move them to a font directory
6. Refresh the font cache
7. **Repeat per family, per machine**

</td><td>

```bash
nerd-fonts-installer --dry-run
nerd-fonts-installer
```

…driven by four lines of YAML you keep in
your dotfiles. **That's it.**

</td></tr>
</table>

Reach for it when you provision dev containers, rebuild laptops, or just want the same glyphs in Starship,
Neovim, tmux, WezTerm or Alacritty on every machine you touch — without a JVM or a Go toolchain to run it.

[`worxbend/nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer) is the Go original this
project re-implements from scratch in Scala 3, targeting the same flags, exit codes, config format and
install layout, and shipping as a GraalVM native binary instead of a JVM jar.
[`docs/PARITY.md`](docs/PARITY.md) is the measured, scenario-by-scenario diff between the two binaries; see
[Differences from the Go implementation](#differences-from-the-go-implementation) below for the short list
of gaps kept on purpose.

## Quick start

### 1. Install the binary

```bash
curl --proto '=https' --tlsv1.2 -sSfL \
  https://github.com/worxbend/nerd-fonts-installer-scala/releases/download/latest/install.sh | sh
```

`scripts/install.sh` detects `{linux,macos}-{amd64,arm64}`, downloads the matching tarball and
`checksums.txt` from the same GitHub release, verifies the SHA-256, and installs into `~/.local/bin` — no
root, no package manager. Set `NERD_FONTS_INSTALLER_VERSION=v0.1.0` before the pipe to pin an exact tag, or
`NERD_FONTS_INSTALLER_INSTALL_DIR` to change where the binary lands.

<details>
<summary>Build from source instead (what CI does)</summary>

<br>

Nothing to install beyond `git` and the repo's `./mill` wrapper — it fetches Mill, and Mill fetches GraalVM
Community for JDK 25 as the build's own toolchain. No system JDK, no `GRAALVM_HOME`.

```bash
git clone https://github.com/worxbend/nerd-fonts-installer-scala
cd nerd-fonts-installer-scala
./mill --no-daemon show app.nativeImage
# → a path ending in out/app/nativeImage.dest/native-executable

out/app/nativeImage.dest/native-executable --version
```

Just want to try it without waiting for `native-image` to link? Run it on the JVM straight from source
(arguments go directly after `app.run` — this repo's `./mill` wrapper drops everything after a bare `--`,
so don't use one):

```bash
./mill --no-daemon app.run --version
./mill --no-daemon app.run --config config.example.yaml --dry-run
```

</details>

### 2. Write a config

```yaml
# ~/.config/nerd-fonts-installer/config.yaml
release: latest
destination: ~/.local/share/fonts/NerdFonts
refresh_font_cache: true
families:
  - JetBrainsMono
  - Hack
  - FiraCode
  - Meslo
```

(`config.example.yaml` in the repo root is exactly this, plus comments — copy it.) Not sure what a family
is called? Ask the tool — it prints paste-ready names for whichever release you're pinned to:

```bash
nerd-fonts-installer --font-names
```

### 3. Preview, then install

```bash
nerd-fonts-installer --dry-run   # shows every URL and destination, touches nothing
nerd-fonts-installer             # downloads, verifies, extracts, installs
```

Four families install concurrently, each verified against the release's `SHA-256.txt` before it touches
disk. Then select the patched font — e.g. **JetBrainsMono Nerd Font**, not `JetBrainsMono` — in your
terminal or editor preferences and restart it.

---

## Configuration

Format is picked by the file's extension: `.json` (case-insensitive) decodes as JSON, everything else —
`.yaml`, `.yml`, `.conf`, no extension — decodes as YAML. Both decoders are **strict**: an unknown key or a
value of the wrong shape fails the load with the field named, never silently coerced or ignored.

| Key | Required | Default | Notes |
| --- | :-: | --- | --- |
| `release` | no | `latest` | `latest` or a tag such as `v3.4.0`, for a reproducible fleet |
| `destination` | no | `~/.local/share/fonts/NerdFonts` | a bare `~` or leading `~/` expands to your home directory; anything else is used as typed |
| `refresh_font_cache` | no | `false` | runs `fc-cache -f <destination>` after a successful install; skipped with a warning if `fc-cache` is not on `PATH` |
| `families` | **yes** | — | archive names without `.zip`; run `--font-names` for the exact list of the selected release; duplicates are a load error |

<details>
<summary>Where config files are discovered</summary>

<br>

Highest priority first — the first candidate that *exists* wins; one that exists but fails to load is a
fatal error, never silently skipped in favour of the next candidate:

1. `--config <path>`
2. `$NERD_FONTS_INSTALLER_CONFIG` (also honoured by `--font-names`; a blank value falls through)
3. `./nerd-fonts-installer.{yaml,yml,json,conf}`
4. `./nerd-fonts-installer/config.{yaml,yml,json,conf}`
5. The same two shapes under `$XDG_CONFIG_HOME` (if absolute) or `~/.config`

Only when every candidate comes up empty do you get the exact candidate list back on stderr, exit code `2`:

```text
no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: …
```

</details>

## Pinning releases

Two independent things can be pinned:

1. **The tool itself** — pass `NERD_FONTS_INSTALLER_VERSION=vX.Y.Z` to `install.sh`, or download an exact
   tagged asset directly. Every tagged release is immutable; a separate moving `latest` pre-release tracks
   `main` for early testing and is never what `install.sh`'s default resolves to for a stable install.
2. **The Nerd Fonts release** — set `release: v3.4.0` (or any published tag) instead of `latest` in the
   config, so every machine gets the exact same font bytes today and in six months.

Pinning `release:` also changes what the tool talks to: with a tag pinned, `--dry-run` and a real install
build every download URL directly and **never call the GitHub releases-listing API at all** — only
`--font-names` browses it, because only that command needs to. A fleet running a pinned
config in parallel is not subject to GitHub's unauthenticated API rate limit.

---

## Command reference

```text
$ nerd-fonts-installer --help
Nerd Fonts, installed the boring way.
Usage: nerd-fonts-installer [-h] [-dry-run] [-font-names] [-version]
                            [-config=<path>]
Install Nerd Fonts from a config file.
      -config, --config=<path>
                            config file; when omitted, discover an app-named
                              config in CWD or the user config directory
      -dry-run, --dry-run   print planned downloads without installing fonts
      -font-names, --font-names
                            print YAML-ready Nerd Font family names and exit
      -version, --version   print version information and exit
  -h, -help, --help         print this help and exit
```

<img src="assets/screenshots/cli-help.svg" alt="nerd-fonts-installer --help output" width="620" />

Captured verbatim from a locally built binary — not retyped. Every option accepts a single dash too
(`-dry-run`), matching Go's `flag` package byte-for-byte.

| Flag | What it does |
| --- | --- |
| `--config <path>` | Use a specific config file, bypassing discovery. |
| `--dry-run` | Print the plan (`•`/`↻` lines) without any network or filesystem write. |
| `--font-names` | Print `# <tag>` + `families:` YAML for the selected release, then exit. |
| `--version` | Print `nerd-fonts-installer <version> (<commit>, <date>)`. |
| `-h`, `--help` | Print this usage and exit — the one deliberate deviation from Go, see below. |

```bash
$ nerd-fonts-installer --version
nerd-fonts-installer 0.1.0 (64af793607b8, unknown)

$ nerd-fonts-installer --font-names
# v3.4.0
families:
  - 0xProto
  - 3270
  - AdwaitaMono
  … (one line per archive in the selected release)

```

`--help` and `--version` print to **stdout** and exit **0** — deliberately, so `nerd-fonts-installer --help
| less` behaves the way everything else piped through `less` does (Go's stdlib `flag` exits `2` on
`--help`, an artefact of that package, not a design choice).

| Stream | Carries |
| --- | --- |
| **stdout** | `--font-names`/`--help`/`--version` output; `--dry-run` plan lines (`• Would install …`, `↻ Would refresh font cache for …`); `fc-cache`'s own inherited output, if it runs |
| **stderr** | `Using config <path>` (discovered config only), per-family progress (`⠋ Installing …`, `✅ Installed …`), the checksum-manifest warning, font-cache status lines, and every error message |

So `nerd-fonts-installer --dry-run > plan.txt` gives a clean, diffable plan with zero noise, and redirecting
stderr to a log captures progress and failures without polluting anything a script parses.

> [!WARNING]
> **Parsing stops at the first positional argument, matching Go's own `flag` package** — everything after
> it, including recognised flags, is swallowed and ignored rather than rejected. `nerd-fonts-installer
> stray-arg --dry-run --config fonts.yaml` silently drops both `--dry-run` and `--config`, falling through
> to normal discovery and running a **real** install. Always put flags first; never place a bare word
> before them on the command line.

---

## Install layout

```yaml
destination: ~/.local/share/fonts/NerdFonts
families: [JetBrainsMono, Hack]
```

produces:

```text
~/.local/share/fonts/NerdFonts/
├── JetBrainsMono/
│   ├── JetBrainsMonoNerdFont-Regular.ttf
│   └── …                                    (only .ttf/.otf/.ttc, flattened)
└── Hack/
    ├── HackNerdFont-Regular.ttf
    └── …
```

Each family is extracted into `<destination>/.<Family>-<random>`, verified, then **renamed** into place: a
stale `<Family>.old` is removed, the live directory is renamed to `.old`, the staging directory is renamed
over it, then `.old` is deleted best-effort. The rename is the commit point — before it, a failed download,
mismatched checksum, hostile archive, deadline or `Ctrl-C` leaves the *previous* `<Family>` untouched. Up to
four families install concurrently; each one's paths are disjoint, which is what makes that safe. There is
no "already installed, skip it" fast path — every run fully re-downloads and replaces every configured
family, by design.

---

## Exit codes

| Situation | Code |
| --- | --- |
| Success, dry run, `--font-names`, `--help`, or `--version` | `0` |
| Malformed flag, unknown option, or missing option value | `2` |
| No config found; unknown release tag; no releases at all | `2` |
| Everything else: config load/parse/validation, network, filesystem, extraction, checksum mismatch, `fc-cache` failure, an interrupted install | `1` |
| A second `Ctrl-C` while the first is still unwinding | `130` |

This is a closed, stable set, safe under `set -e`:

```bash
nerd-fonts-installer --config fonts.yaml || case $? in
  2) echo "fix the config or the flags, then retry" >&2 ;;
  1) echo "transient/runtime failure — safe to retry" >&2 ;;
esac
```

---

## Troubleshooting

<details>
<summary><code>no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: …</code></summary>

<br>

Point at a file explicitly, or drop one where discovery looks:

```bash
nerd-fonts-installer --config /path/to/fonts.yaml
mkdir -p ~/.config/nerd-fonts-installer && $EDITOR ~/.config/nerd-fonts-installer/config.yaml
```

</details>

<details>
<summary><code>duplicate font family "Hack"</code></summary>

<br>

The same family appears twice under `families:`. Remove the repeat — the config loader rejects it before
any network call is made.

</details>

<details>
<summary><code>install fonts: install Nerd Font family X: download …: 404 Not Found</code></summary>

<br>

Almost always a stale or mistyped family name, or a `release:` tag that doesn't have that archive. Run
`nerd-fonts-installer --font-names` (with the same `release:` your config uses) and copy the exact stem
from its output — it's the raw asset name for the selected release, not a display name.

</details>

<details>
<summary><code>checksum mismatch for X: downloaded sha256 …, expected …</code></summary>

<br>

Fatal by design and will not be relaxed (see [Security](#security-in-brief)): a missing `SHA-256.txt` only
warns and installs unverified, but a **present, mismatching** digest stops the whole run and cancels any
families still downloading. Re-run — a corrupted download is far more likely than a tampered release — and
open an issue if it persists against the same release tag.

</details>

<details>
<summary>The native binary won't start / prints an illegal instruction on Linux</summary>

<br>

`linux-amd64`/`linux-arm64` releases are dynamically linked against glibc and need **glibc ≥ 2.34**
(Ubuntu 22.04+, Debian 12+, RHEL/Rocky 9+). The build pins `-march=compatibility` specifically so the CPU
itself is never the problem — an older glibc is the one thing that still is. Build from source with your
own toolchain (`./mill --no-daemon show app.nativeImage`) if you're stuck on an older base image.

</details>

<details>
<summary>Icons still look like boxes after the install</summary>

<br>

Installing the font is only half of it — select the *patched* name in your terminal or editor's font
preference, e.g. `JetBrainsMono Nerd Font`, not `JetBrainsMono`. Restart the app; most terminals cache the
font list at launch.

</details>

<details>
<summary>Notes for macOS</summary>

<br>

`fc-cache` usually isn't present, so `refresh_font_cache: true` just emits a one-line warning
(`fc-cache is not available; skipping font cache refresh.`) and the install still succeeds. Font Book picks
up `~/Library/Fonts` automatically if you point `destination` there instead.

</details>

<details>
<summary>I sent a second <code>Ctrl-C</code> and now there's a stray <code>.old</code> or <code>.ttf</code>-less staging directory</summary>

<br>

Expected: the first `Ctrl-C` interrupts the main thread so every `finally` runs (temp zip removed, staging
dir removed, terminal restored) and exits `1` with `install fonts: interrupted`. A **second** `Ctrl-C`
while that cleanup is still in flight halts the process immediately with `130` — the one intentional escape
hatch for a cleanup that itself hangs, and Go doesn't offer it. The next run removes a stale `<Family>.old`
on its own; a leftover temp zip under `$TMPDIR` is safe to delete by hand.

</details>

<details>
<summary>A stray word on the command line silently turned my dry run into a real install</summary>

<br>

See the warning under [Command reference](#command-reference): parsing stops at the first positional
argument, and everything after it — including `--dry-run` and `--config` — is ignored rather than rejected,
matching Go's own `flag` package. Keep flags first, always; a bare word anywhere on the line before your
flags will silently drop them.

</details>

---

## Security in brief

Full threat model, control list, and what's explicitly out of scope: [`docs/SECURITY.md`](docs/SECURITY.md).
Report a vulnerability via [GitHub private advisories](https://github.com/worxbend/nerd-fonts-installer-scala/security/advisories/new),
never a public issue.

- **`FamilyName.parse` is the single path-traversal guard.** Every family name touches a path or URL only
  after passing it — rejects empty, `.`, `..`, any `/` or `\`, NUL, absolute paths, and anything whose base
  name differs from itself. `Release.families` (raw upstream stems) stays untyped `String` on purpose:
  display data until it crosses this one boundary.
- **Every network body is byte-capped**, enforced by the port itself so the test fake exercises the same
  cap logic as production:

  | Cap | Value | Applies to |
  | --- | --- | --- |
  | download | 768 MiB | one font archive |
  | font file | 128 MiB | one entry inside an archive |
  | archive | 2 GiB | total bytes extracted from one archive |
  | manifest | 1 MiB | `SHA-256.txt` (truncated, not rejected) |
  | API page | 8 MiB | one page of the GitHub releases API |

- **A missing checksum manifest warns; a mismatching one is fatal.** Never weakened (`AGENTS.md`).
- **Extraction trusts nothing in the archive**: only `.ttf`/`.otf`/`.ttc` entries, flattened to their base
  name (no zip-slip, no symlinks, no directories), declared size checked before inflating.
- **Installs are staged, then renamed** — see [Install layout](#install-layout).
- **No shell, ever.** `fc-cache` and `stty` run from an argv vector through `ProcessBuilder`, never `sh -c`.
- HTTPS-only production URLs, TLS defaults from `java.net.http`, a 30 s connect timeout with every overall
  deadline owned by the caller via `timeoutEither` (never the throwing `ox.timeout`).

---

## Development

### The gate

Every change must pass all of these before it's committed; CI (`.github/workflows/checks.yml`) runs the
same commands on `ubuntu-24.04` and `macos-15`.

| Check | Command |
| --- | --- |
| Format | `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` |
| Scalafix | `./mill --no-daemon __.fix --check` |
| Compile (`-Werror`) | `./mill --no-daemon __.compile` |
| All tests | `./mill --no-daemon __.test` |
| One module's tests | `./mill --no-daemon core.test` |
| Smoke from source | `./mill --no-daemon app.run --config config.example.yaml --dry-run` |
| Native binary | `./mill --no-daemon show app.nativeImage` |

`./mill` is the checked-in wrapper (no local Mill install needed); it fetches Mill 1.1.7, which fetches
`graalvm-community:25.0.1` for every module's `jvmVersion` — one toolchain compiles, tests, and links the
native image, so there's no separate GraalVM setup step anywhere, including in CI.

### Tests, measured

```
$ ./mill --no-daemon __.test
…
510/510, SUCCESS] ./mill __.test 13s
```

(`510` is Mill's task count for the whole build graph — compiling and testing every module.) The tests
themselves, run against this repository:

| Module | Tests | Fakes it drives against |
| --- | --- | --- |
| `core` | 253 | `InMemoryHttpClient`, `FakeProcessRunner`, `FontZips`, `GatedHttpClient` (latch-held requests for interrupt/deadline tests) |
| `config` | 81 | `ConfigFiles` (fixture writer) |
| `cli` | 92 | `Fakes` (every `AppDependencies` seam replaced with a pure function) |
| `app` | 2 | reflection walk of every `@Command` class against `reflect-config.json` |
| **Total** | **428**, 0 failed, 0 ignored | across **40** test classes |

Nothing here touches the network or a real terminal: `core` tests the JDK HTTP adapter against a loopback
`com.sun.net.httpserver.HttpServer`. One property suite (`FamilyNamePropertySuite`, ScalaCheck) fuzzes
`FamilyName.parse` against a hazardous-character alphabet instead of enumerating cases by hand.

### The native binary, measured

Built locally with `./mill --no-daemon show app.nativeImage` on linux-amd64:

| Metric | Value | How it was measured |
| --- | --- | --- |
| Binary size | **~42 MB**, stripped | `stat --format=%s` on `out/app/nativeImage.dest/native-executable` |
| Startup, `--version` | **5.4 ms** ± 0.8 ms (range 4.2–10.2 ms, 511 runs) | `hyperfine --shell=none --warmup 10` |
| For comparison: the JVM assembly jar | 15 MB jar, but **670 ms** ± 31 ms to start (`java -jar … --version`) | `./mill --no-daemon app.writeAssembly`, then `hyperfine` |

The ~124× startup gap is the entire reason this project builds a native image instead of shipping a jar:
`app.writeAssembly` exists as a local convenience (`dist/nerd-fonts-installer.jar`) and is explicitly never
published (`docs/SPEC.md` §10). Numbers above are one measurement on one machine — expect variance, not
these exact figures, on yours.

### The stack, and why each piece

| Concern | Choice | Why |
| --- | --- | --- |
| Language | Scala 3.8.4, direct-style (braceless syntax, explicit return types on public members) | `Either` + sealed error ADTs read like Go's `if err != nil` chains without the boilerplate; opaque types (`FamilyName`, `ReleaseTag`, `ByteLimit`, `Sha256Digest`, …) make an unvalidated `String` a compile error at every trust boundary |
| Concurrency | [Ox](https://ox.softwaremill.com) 1.0.6 — `supervised`, `Flow.mapParUnordered`, `Actor`, `timeoutEither` | Structured concurrency without Futures: the four-way font fan-out is a `supervised` scope + `Flow`, and Go's `errgroup` "first failure cancels the rest" semantics fall out of scope teardown for free |
| CLI parsing | [picocli](https://picocli.info) 4.7.7 | Reflection-based option binding that still native-images cleanly with a hand-maintained `reflect-config.json`; both `-flag`/`--flag` spellings declared per option to match Go's `flag` package byte-for-byte |
| Build | [Mill](https://mill-build.org) 1.1.7 via the checked-in `./mill` wrapper | One `build.mill`, no plugins beyond scalafix; `jvmVersion` on every module points at `graalvm-community:25.0.1`, which Mill fetches itself — the same toolchain compiles, tests **and** links the native image |
| Native image | GraalVM Community for JDK 25, `--no-fallback -O2 -march=compatibility` | `-march=compatibility` matches Go's `GOAMD64=v1` baseline so the binary doesn't refuse to start on a pre-2013 CPU or a default-model VM/container — GraalVM 25's AMD64 default (`x86-64-v3`) would |
| YAML/JSON | `scala-yaml` + `ujson`, AST only, **no derivation** | Strict, hand-written decoding lets the config loader reproduce yaml.v3's and `encoding/json`'s exact coercion rules instead of whatever a generic derivation macro decides |
| Tests | munit 1.3.6 + munit-scalacheck | One scenario per test, named as a sentence; a property suite for the one thing that must hold for *any* input (`FamilyName.parse`) |
| Style enforcement | `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement`, scalafix `DisableSyntax` (no `var`/`null`/`return`/`while`) | The compiler and linter enforce the invariants below instead of a code-review checklist |

---

## How it is put together

```
                     ┌─────────┐
                     │   app   │   Main, SIGINT handler, native-image reflect-config
                     └────┬────┘
                          │
                     ┌────▼────┐
                     │   cli   │   picocli boundary, Application, exit codes, event renderer
                     └────┬────┘
             ┌────────────┘
        ┌────▼───┐   ┌────────┐
        │ config │──▶│  core  │
        └────────┘   └────────┘
```

`core` has no outgoing edges to any other module — it never imports picocli, fansi, or terminal code, so it
compiles (and tests) without ever touching a process boundary.

| Module | Third-party deps | Owns |
| --- | --- | --- |
| `core` | ox, os-lib, upickle | Domain values (`FamilyName`, `ReleaseSelector`, …), every port (`HttpClient`, `ProcessRunner`, `Environment`), the GitHub release catalogue, and the install engine (`FontInstaller`) |
| `config` | scala-yaml, upickle | Strict YAML/JSON decoding into `InstallConfig`, defaults, discovery |
| `cli` | picocli, fansi | The picocli command surface, `Application` (the run after flag parsing — never sees `args` or picocli types), exit codes, `ConsoleEventRenderer`, the composition root, generated `BuildInfo` |
| `app` | — | `Main`, SIGINT → thread-interrupt wiring, native-image reflection config |

`docs/ARCHITECTURE.md` is the long version: **21 numbered invariants** a change may never silently move
(the family-name trust boundary, every body being byte-capped, staged-then-renamed installs, the sink being
serialised by the engine and never by the sink, …), plus a decision log explaining *why* each non-obvious
choice was made — read it before touching `install`.

---

## Differences from the Go implementation

Parity with [`worxbend/nerd-fonts-installer`](https://github.com/worxbend/nerd-fonts-installer) is a hard
requirement, not a nice-to-have: same exit codes, same config discovery order, same `--font-names`/dry-run
output shapes, same atomic install layout.
[`docs/PARITY.md`](docs/PARITY.md) records the measured comparison — both binaries run with identical
arguments and environment, streams diffed with `diff`, scenario by scenario.

| | Go | Scala |
| --- | --- | --- |
| Concurrency | goroutines + `errgroup` | Ox `supervised` scope + `Flow.mapParUnordered` + one private cancellation exception |
| Cancellation | `context.Context` | thread interruption (`InterruptedException` propagates out of every port) |
| CLI parsing | `flag` (stdlib) | picocli, both dash spellings declared per option |
| Distribution | statically-linked Go binary, tarballs + a Snap package + a project website/wiki | GraalVM native image, dynamically linked against glibc on Linux; tarballs + `install.sh` + `checksums.txt` only — no Snap package, no website, no wiki |

Deviations kept on purpose (all documented, none accidental):

| Deviation | Why |
| --- | --- |
| `--help` prints to stdout and exits 0 (Go: stderr, exit 2) | `--help \| less` is what people actually do; Go's exit 2 is an artefact of its `flag` package |
| A malformed command line prints picocli's own first line, not Go's `flag provided but not defined` — same exit code and stream | Not machine-parsed; the prefix, stream and exit code scripts actually check are identical |
| A second `Ctrl-C` halts the process with 130 (Go absorbs repeats) | An intentional escape hatch for a cleanup that itself hangs |

Parsing stops at the first positional argument on both binaries — that one isn't a deviation, it's shared
behaviour inherited from Go's `flag` package, and exactly why it's called out as a
[footgun](#command-reference) above rather than buried in a diff table.

---

## Credits

Fonts come from [ryanoasis/nerd-fonts](https://github.com/ryanoasis/nerd-fonts) — this project only installs
them. The behavioural contract comes from
[worxbend/nerd-fonts-installer](https://github.com/worxbend/nerd-fonts-installer) (Go). Built on
[Ox](https://ox.softwaremill.com) for structured concurrency, [picocli](https://picocli.info) for the CLI
surface, [Mill](https://mill-build.org) for the build, [munit](https://scalameta.org/munit/) for tests, and
[GraalVM](https://www.graalvm.org) native image for distribution.

## License

[MIT](LICENSE) © 2026 w0rxbend.

<div align="center">
<br>

**If this saved you a few minutes on your next machine, a ⭐ is appreciated.**

</div>

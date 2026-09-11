# Security

This document describes what `nerd-fonts-installer` trusts, which controls the code enforces against
untrusted input, what it does not defend against, and how to report a vulnerability. File paths below are
the authoritative source; when this document and the code disagree, the code is right and this document has
a bug.

## Reporting a vulnerability

Use GitHub private vulnerability reporting on the repository:
<https://github.com/worxbend/nerd-fonts-installer-scala/security/advisories/new>. Do not open a public issue
for a suspected vulnerability. Include the `nerd-fonts-installer --version` line, the platform, and a
reproduction (a config file and the command line are usually enough). Only the latest release is supported;
fixes ship as a new tagged release, and the advisory is published once that release is available.

## Threat model

The tool runs as an ordinary user, reads a config file the user wrote, downloads zip archives and a
checksum manifest from GitHub, extracts font files into a directory under the user's control, and
optionally runs `fc-cache`.

| Input | Trust | Why |
| --- | --- | --- |
| Command line, environment variables, config file | Trusted | They belong to the invoking user. A config can point `destination` anywhere the user can write; that is the feature, not a flaw. |
| GitHub releases API response | Untrusted | Parsed strictly; asset stems are display data until validated. |
| `SHA-256.txt` manifest | Untrusted | Parsed leniently, used only to verify archives. |
| Font zip archives | Untrusted | Every entry name and size is treated as hostile. |
| `$TMPDIR`, `$PATH` | Trusted | They decide where downloads are staged and which `fc-cache` / `stty` runs, as they do for any command-line tool. |

The attacker considered is a network position between the user and GitHub, or a hostile or corrupted
release asset, whose goal is to write outside the destination, exhaust disk or memory, or leave the
destination in a half-installed state. A compromise of the user's account, environment or machine is out
of scope.

## Controls in this code

### Family names are the single path-traversal guard

`core/src/io/worxbend/nerdfonts/fonts/FamilyName.scala`. A family name is joined onto the destination
directory (`<root>/<Family>`, `<root>/.<Family>-<random>`, `<root>/<Family>.old`) and onto the download URL,
so every name passes `FamilyName.parse` first. It rejects the empty string, `.`, `..`, any `/` or `\`, NUL,
a leading `/`, and anything whose base name differs from itself. The three places a raw string becomes a
`FamilyName` are the config loader (`InstallConfig.validated`), the picker (`PickerOutcome.of`) and the
checksum-manifest parser (an unparseable stem is skipped, never used). `Release.families` stays
`Vector[String]` on purpose: upstream asset stems are printed by `--font-names` and shown in the picker but
never touch a path or URL. URL segments are additionally percent-escaped with Go's `url.PathEscape` rules
(`ReleaseUrls`).

### Every network body is byte-capped

`core/src/io/worxbend/nerdfonts/install/SizeLimits.scala` holds the caps; `ResponseDelivery` and
`BoundedInputStream` (`core/src/io/worxbend/nerdfonts/http/`) enforce them for every `HttpClient`
implementation, including the test fake, so the production cap logic is what the tests exercise.

| Cap | Value | Applies to |
| --- | --- | --- |
| `download` | 768 MiB | one font archive |
| `fontFile` | 128 MiB | one entry inside an archive |
| `archive` | 2 GiB | total bytes extracted from one archive |
| `manifest` | 1 MiB | `SHA-256.txt` (truncated, not rejected) |
| `apiPage` | 8 MiB | one page of the GitHub releases API (`GitHubReleaseCatalogue.defaultPageLimit`) |

A `Content-Length` above the cap is refused before a byte is read; without a header, the stream is capped
at `limit + 1` and reading past the limit turns the result into `HttpError.TooLarge` even though the consumer
returned normally. Bodies are streamed, never held in memory: the archive is hashed with a
`DigestInputStream` while it is copied to disk. Non-2xx responses are refused before the body is handed over.

### Extraction trusts nothing in the archive

`core/src/io/worxbend/nerdfonts/install/ArchiveExtractor.scala`. Only entries whose extension is `.ttf`,
`.otf` or `.ttc` (case-insensitive) are written, and each is flattened to its base name, so a path inside the
zip never decides where a byte lands (no zip-slip, no directories, no symlinks are created). An entry's
declared size is refused before it is inflated, the inflating stream is capped at `fontFile + 1` so a lying
header is caught too, and a running total enforces `archive`. Every written file is `fsync`ed and closed
explicitly; a flush or close error fails the install instead of promoting a truncated font. An archive with
zero font files is an error.

### Checksums: a missing manifest warns, a mismatch is fatal

`FontInstaller.fetchDigests` / `FontInstaller.verify` in
`core/src/io/worxbend/nerdfonts/install/FontInstaller.scala`. The release's `SHA-256.txt` is fetched once,
under a 30 s deadline and the 1 MiB cap. If it cannot be fetched or parsed the run prints
`• Checksum manifest unavailable (<cause>); installing without integrity verification.` and continues, so
GitHub's release page being flaky does not brick an install. If a digest for the family is present and
differs from the SHA-256 of the downloaded bytes, the family fails with
`checksum mismatch for <Family>: downloaded sha256 <got>, expected <want>`, the fan-out stops, in-flight
siblings are cancelled, and the process exits 1. A family absent from the manifest installs unverified.
This policy mirrors the Go reference and must not be weakened (AGENTS.md).

### Installs are staged, then renamed

`core/src/io/worxbend/nerdfonts/install/DirectorySwap.scala`. A family is extracted into
`<root>/.<Family>-<random>` (created with `Files.createTempDirectory` semantics, on the same filesystem as
the target so the rename is `rename(2)`). The swap removes a stale `<Family>.old`, renames the existing
`<Family>` to `.old`, renames the staging directory into place, then best-effort deletes `.old`. The second
rename is the commit point: before it, a failure restores the previous fonts; after it, a cleanup failure is
never reported. An existing `<root>/<Family>` is therefore untouched by a failed download, a checksum
mismatch, a hostile archive, a deadline or an interrupt. Per-family paths are disjoint and
`InstallPlan.of` de-duplicates, so the four concurrent workers never share a path.

### No shell, ever

`core/src/io/worxbend/nerdfonts/process/JdkProcessRunner.scala`. Subprocesses are started from an argv
vector through `ProcessBuilder`; nothing is ever passed through `sh -c`. The only commands run are
`fc-cache -f <root>` (streams inherited) and, for the interactive picker, `stty -g`, `stty raw -echo`,
`stty <saved>` and `stty size` with stdin redirected from `/dev/tty`. Programs are resolved on `$PATH`
through the `Environment` port before launch; a child still alive when the run is interrupted is destroyed
before the interrupt propagates.

### HTTPS and redirects

`core/src/io/worxbend/nerdfonts/http/JdkHttpClient.scala`. Production URLs are built from two constant
HTTPS bases (`https://github.com/ryanoasis/nerd-fonts/releases` and
`https://api.github.com/repos/ryanoasis/nerd-fonts/releases`) plus escaped segments; no URL comes from a
config file or the network. The client is `java.net.http` with `Redirect.NORMAL`, which follows GitHub's
`releases/download` hop to object storage but refuses an `https` to `http` downgrade. TLS validation is
`java.net.http`'s default; the native image carries the build JDK's `cacerts` trust store, which
`javax.net.ssl.trustStore` can override at run time. A 30 s connect timeout is the only client-level timeout;
callers own overall deadlines (30 s per API page, 30 s for the manifest, 10 minutes per family) through
`timeoutEither`, so a stalled transfer cannot hang the process indefinitely. Requests send
`User-Agent: nerd-fonts-installer` and nothing else that identifies the user.

`NERD_FONTS_INSTALLER_BASE_URL` is a test hook read once by `AppDependencies.production`: a non-blank value
replaces the release base, including with a plain-`http` URL, which is how `scripts/ci/interrupt-smoke.sh`
aims the shipped binary at a local stub. It is deliberately undocumented for users and is covered by the
"environment is trusted" line above; an attacker who can set it can already run arbitrary code as the user.

### Temporary files

Archives are downloaded to `nerd-font-<random>.zip` under `$TMPDIR` (when set and non-empty) or the JDK's
`java.io.tmpdir`, created through `os.temp`, that is `Files.createTempFile`, which chooses an unpredictable
name and fails rather than reuse an existing one. The file is removed in a `finally` block whatever happens
to the install. Staging directories live under the destination root with a dot prefix so font tooling that
scans the directory mid-install does not pick them up, and are removed the same way. A leftover
`<Family>.old` from a crash is removed at the start of the next swap for that family.

### Interrupts clean up

`app/src/io/worxbend/nerdfonts/app/Main.scala`. SIGINT is turned into an interrupt of the main thread
instead of the JVM's default exit, which would not unwind `finally` blocks. The Ox scopes end, in-flight
downloads abort, every temp zip and staging directory is removed, the terminal's `stty` settings, alternate
screen and cursor are restored, and the process exits 1 with `install fonts: interrupted`. A second SIGINT
while that is in progress halts the process with status 130 and may leave scratch files behind; that is
the one escape hatch for a cleanup that hangs. `scripts/ci/interrupt-smoke.sh` runs this scenario against
the real native binary on every CI run and asserts that nothing is left under `$TMPDIR` or the destination.

### Strict configuration

`config/src/io/worxbend/nerdfonts/config/`. Unknown keys, repeated YAML keys and values of the wrong shape
fail the load with the field named; nothing is coerced beyond the documented YAML scalar rules. Only a bare
`~` or a leading `~/` in `destination` is expanded. A discovered config that exists but cannot be read,
parsed or validated is an error, never silently skipped in favour of the next candidate or the picker.

### Release artefacts

`release.yml` builds each native image on its own runner, packages it as a `tar.gz` with a `.sha256`
sidecar, and publishes a combined `checksums.txt` and `scripts/install.sh` with every release.
`install.sh` downloads over TLS 1.2+ only (`--proto '=https' --tlsv1.2`), verifies the archive against
`checksums.txt` from the same release before extracting, and installs without root into
`~/.local/bin` by default. The checksums and the script come from the same GitHub release as the binary, so
they protect against a truncated or corrupted download, not against a compromised release.

## What is not protected against

- **A compromised upstream.** The manifest and the archives come from the same Nerd Fonts release. If the
  release itself is tampered with, the manifest will match the tampered archive. Nerd Fonts publishes no
  signatures, so none are verified.
- **Malicious font files.** Only the container is inspected. A `.ttf` that exploits a font rasteriser is
  installed as faithfully as a benign one; that is the font stack's threat model, not this tool's.
- **A hostile config or environment.** `destination` may be any writable path, and the tool will create it,
  replace `<destination>/<Family>` and remove `<destination>/<Family>.old`. `$PATH` decides which
  `fc-cache` and `stty` run. `NERD_FONTS_INSTALLER_BASE_URL` redirects every download.
- **Concurrent writers.** Two simultaneous runs against the same destination, or another process editing
  `<destination>/<Family>` during the swap, are not coordinated; the swap is atomic per rename, not per run.
- **Running as root.** Nothing stops it. The tool installs into whatever `destination` resolves to for the
  invoking user and runs `fc-cache` with that user's privileges.
- **A second SIGINT.** It halts immediately, by design, and may leave `nerd-font-*.zip` or `.<Family>-*`
  scratch files behind. The next run removes a stale `.old`; the temp zip and staging directory must be
  deleted by hand.
- **The install script's first hop.** `curl … | sh` trusts TLS to `github.com`. Anyone who wants more can
  download `install.sh` and `checksums.txt` first and read both before running anything.

## Invariants for contributors

AGENTS.md lists the security invariants that a change may never weaken: every family name passes
`FamilyName.parse` before touching a path or URL; downloads and extraction are byte-capped; a checksum
mismatch is fatal; installs are staged then renamed. `docs/ARCHITECTURE.md` explains each in detail, and
the test suites named in `docs/SPEC.md` §9 (`FamilyNamePropertySuite`, `HttpClientContractSuite`,
`ArchiveExtractorSuite`, `DirectorySwapSuite`, `FontInstallerSuite`) are the regression net. A pull request
that touches any of them should say so in its description.

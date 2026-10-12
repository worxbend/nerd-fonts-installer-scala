# Dependency maintenance

Audited on 2026-10-13. Pins target stable releases with downloadable artifacts for this Scala 3 build;
release candidates, milestones and snapshots are excluded. `Versions.project` is the application's
release version and is not changed by dependency maintenance.

| Dependency or tool | Version | Verification source |
| --- | --- | --- |
| Scala | 3.10.0 | [Release](https://github.com/scala/scala3/releases/tag/3.10.0) |
| Mill | 1.3.0 | [Release](https://github.com/com-lihaoyi/mill/releases/tag/1.3.0) |
| GraalVM Community | 25 Innovation 4: Graal 25.4.4.1.1, JDK 25.0.4.1.1 | [Release and platform downloads](https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.4.4.1.1) |
| ZIO, streams and test modules | 2.1.26 | [Scala 3 Maven metadata](https://repo.maven.apache.org/maven2/dev/zio/zio_3/maven-metadata.xml) |
| zio-config and its yaml/typesafe/magnolia modules | 4.1.0 | [Scala 3 Maven metadata](https://repo.maven.apache.org/maven2/dev/zio/zio-config_3/maven-metadata.xml) |
| zio-http | 3.11.6 | [Release](https://github.com/zio/zio-http/releases/tag/v3.11.6) |
| zio-json | 1.1.0 | [Scala 3 Maven metadata](https://repo.maven.apache.org/maven2/dev/zio/zio-json_3/maven-metadata.xml) |
| os-lib | 0.11.8 | [Scala 3 Maven metadata](https://repo.maven.apache.org/maven2/com/lihaoyi/os-lib_3/maven-metadata.xml) |
| fansi | 0.5.1 | [Scala 3 Maven metadata](https://repo.maven.apache.org/maven2/com/lihaoyi/fansi_3/maven-metadata.xml) |
| scalafmt | 3.11.5 | [Release](https://github.com/scalameta/scalafmt/releases/tag/v3.11.5) |
| mill-scalafix | 0.6.2 | [Mill 1 / Scala 3 Maven metadata](https://repo.maven.apache.org/maven2/com/goyeau/mill-scalafix_mill1_3/maven-metadata.xml) |
| Scalafix | 0.14.9 | [Release](https://github.com/scalacenter/scalafix/releases/tag/v0.14.9) |
| actions/checkout | 7.0.1 | [Release](https://github.com/actions/checkout/releases/tag/v7.0.1) |
| actions/cache | 6.1.0 | [Release](https://github.com/actions/cache/releases/tag/v6.1.0) |
| actions/upload-artifact | 7.0.2 | [Release](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) |
| actions/download-artifact | 8.0.2 | [Release](https://github.com/actions/download-artifact/releases/tag/v8.0.2) |
| raven-actions/actionlint | 2.2.0 | [Release](https://github.com/raven-actions/actionlint/releases/tag/v2.2.0) |

ZIO [2.1.27](https://github.com/zio/zio/releases/tag/v2.1.27) and zio-config
[4.1.1](https://github.com/zio/zio-config/releases/tag/v4.1.1) have upstream release tags, but the required
Maven Central POMs return HTTP 404 on both `repo1.maven.org` and `repo.maven.apache.org` at audit time.
Their metadata and all the corresponding streams/test/config module metadata still list 2.1.26 and
4.1.0 respectively. Update these families together once their Scala 3 artifacts are available.
os-lib's newer 0.11.9-M* tags are milestones, so 0.11.8 remains the stable pin.

Versions live in `build.mill`, `.mill-version`, `.scalafmt.conf` and the workflows. When updating Mill,
update the build header, wrapper default and every wrapper digest together. The 1.3.0 launcher digests
were computed from the official Maven Central executables, with each download checked against its
published SHA-1 checksum before computing the pinned SHA-256 value. Do not remove the wrapper's
checksum guard. GraalVM uses official release URLs because Coursier's index does not yet list the
innovation releases; update both the release tag and the distribution filename version in `Versions`.

Dependabot checks GitHub Actions weekly. Scala libraries and tooling require a maintenance change;
verify Maven metadata as well as release tags, then run the gates in [CONTRIBUTING.md](../CONTRIBUTING.md)
and the native HTTP/interrupt smoke scripts after changing the toolchain or HTTP dependencies.

Validation on Linux amd64: all 403 tests passed; compilation with `-Werror`, formatting, Scalafix and
workflow linting passed. The native image built with the pinned GraalVM, and CLI, interrupt cleanup and
a checksum-verified installation against a local HTTP fixture passed with an isolated copy of the binary.
The live GitHub catalogue smoke received HTTP 403 (rate limit); TLS, error rendering and process exit
were verified, but a successful live catalogue listing could not be asserted. Other native targets are
covered by the existing CI release matrix rather than a local cross-build.

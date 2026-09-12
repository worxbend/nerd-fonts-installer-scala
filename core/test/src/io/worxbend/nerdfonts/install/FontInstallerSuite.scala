package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.HttpResponse
import io.worxbend.nerdfonts.http.InMemoryHttpClient
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Body
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response
import io.worxbend.nerdfonts.http.Url
import io.worxbend.nerdfonts.install.EagerAssertion.eagerly
import io.worxbend.nerdfonts.process.ExitStatus
import io.worxbend.nerdfonts.process.FakeProcessRunner
import io.worxbend.nerdfonts.process.FakeProcessRunner.Script
import io.worxbend.nerdfonts.process.ProcessSpec
import io.worxbend.nerdfonts.releases.ReleaseUrls

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import zio.Scope
import zio.Task
import zio.UIO
import zio.ZIO
import zio.test.Spec
import zio.test.TestAspect
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** Every `core/install` scenario of SPEC §6, through the real engine with in-memory ports. */
object FontInstallerSuite extends ZIOSpecDefault:
  private val hack        = family("Hack")
  private val manifestUrl = ReleaseUrls.github.checksums(ReleaseSelector.Latest)
  private val noManifest  = Response.status(404)

  private val pollBudgetNanos: Long = 5_000_000_000L

  private def family(name: String): FamilyName =
    FamilyName.parse(name).getOrElse(throw AssertionError(s"unsafe $name"))

  private def downloadUrl(name: String): Url =
    ReleaseUrls.github.download(ReleaseSelector.Latest, family(name)).url

  private def request(
      root: os.Path,
      families: Vector[FamilyName],
      refresh: RefreshFontCache = RefreshFontCache.Disabled,
      dryRun: DryRun = DryRun.Disabled,
  ): InstallRequest = InstallRequest(ReleaseSelector.Latest, root, families, refresh, dryRun)

  private def routes(manifest: Response, families: String*): Map[Url, Response] = families
    .map(name => downloadUrl(name) -> Response.ok(FontZips.family(name)))
    .toMap + (manifestUrl -> manifest)

  private def noFcCache: FontCacheRefresher = FcCacheRefresher(FakeProcessRunner())

  private def installer(
      ws: os.Path,
      http: HttpClient,
      limits: SizeLimits = SizeLimits.default,
      refresher: FontCacheRefresher = noFcCache,
      familyDeadline: FiniteDuration = FontInstaller.defaultFamilyDeadline,
      manifestTimeout: FiniteDuration = FontInstaller.defaultManifestTimeout,
  ): FontInstaller =
    val tmp = ws / "tmp"
    os.makeDir.all(tmp)
    FontInstaller(http, tmp, refresher, limits, familyDeadline, manifestTimeout)

  private def tempZips(ws: os.Path): Seq[String] = os.list(ws / "tmp").map(_.last)

  private def stagingDirs(root: os.Path): Seq[String] =
    if os.exists(root) then os.list(root).map(_.last).filter(_.startsWith(".")) else Seq.empty

  private def rendered(result: Either[InstallError, Unit]): Either[String, Unit] = result.left.map(_.render)

  private def withWorkspace[A](test: os.Path => Task[A]): Task[A] = ZIO.acquireReleaseWith(
    ZIO.attemptBlockingIO(os.temp.dir(prefix = "font-installer")),
  )(dir => ZIO.attemptBlockingIO(os.remove.all(dir)).orDie)(test)

  private def awaitCondition(what: String)(condition: => Boolean): UIO[Unit] =
    ZIO.attemptBlocking(pollUntil(what, System.nanoTime() + pollBudgetNanos)(condition)).orDie

  @tailrec
  private def pollUntil(what: String, deadlineNanos: Long)(condition: => Boolean): Unit =
    if condition then ()
    else if System.nanoTime() > deadlineNanos then throw AssertionError(s"timed out waiting for $what")
    else
      Thread.sleep(10)
      pollUntil(what, deadlineNanos)(condition)

  override def spec: Spec[TestEnvironment, Any] = suite("FontInstaller")(
    test("a dry run emits the plan and touches neither the network nor the disk"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val http = InMemoryHttpClient(routes(noManifest, "Hack"))
        val sink = RecordingSink()
        val req  = request(root, Vector(hack), refresh = RefreshFontCache.Enabled, dryRun = DryRun.Enabled)
        installer(ws, http).install(req, sink).either.map { result =>
          eagerly(
            assertTrue(
              result == Right(()),
              sink.events == Vector(
                InstallEvent
                  .WouldInstall(
                    hack,
                    ReleaseUrls.github.download(ReleaseSelector.Latest, hack),
                    root / "Hack",
                  ),
                InstallEvent.WouldRefreshCache(root),
              ),
              http.requests == Vector.empty,
              !os.exists(root),
            ),
          )
        }
      }
    ,
    test("a destination that cannot be created fails before any HTTP call"):
      withWorkspace { ws =>
        val blocker = ws / "file"
        ZIO.attemptBlockingIO(os.write(blocker, "not a directory")) *> {
          val http = InMemoryHttpClient(routes(noManifest, "Hack"))
          installer(ws, http).install(request(blocker / "fonts", Vector(hack)), RecordingSink()).either.map {
            result =>
              eagerly(
                assertTrue(
                  result.left.exists(_.isInstanceOf[InstallError.Destination]),
                  rendered(result).left.exists(_.startsWith(s"create destination ${blocker / "fonts"}: ")),
                  http.requests == Vector.empty,
                ),
              )
          }
        }
      }
    ,
    test("installs a single family into <root>/<Family> and reports Started then Installed"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val sink = RecordingSink()
        installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")))
          .install(request(root, Vector(hack)), sink)
          .either
          .map { result =>
            eagerly(
              assertTrue(
                result == Right(()),
                os.read(root / "Hack" / "Hack.ttf") == "font",
                sink.events == Vector(
                  InstallEvent.ChecksumManifestUnavailable("404 Not Found"),
                  InstallEvent.Started(hack, ReleaseUrls.github.download(ReleaseSelector.Latest, hack)),
                  InstallEvent.Installed(hack, root / "Hack"),
                ),
                tempZips(ws).isEmpty,
                stagingDirs(root).isEmpty,
              ),
            )
          }
      }
    ,
    test("an upper-case .TTF entry is extracted"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val http = InMemoryHttpClient(
          Map(
            manifestUrl         -> noManifest,
            downloadUrl("Hack") -> Response.ok(FontZips.deflated("Hack.TTF" -> "font")),
          ),
        )
        installer(ws, http).install(request(root, Vector(hack)), RecordingSink()).either.map { result =>
          eagerly(assertTrue(result == Right(()), os.exists(root / "Hack" / "Hack.TTF")))
        }
      }
    ,
    test("installs many families concurrently with every line intact and each Started before its Installed"):
      withWorkspace { ws =>
        val root              = ws / "fonts"
        val names             = Vector("Hack", "JetBrainsMono", "FiraCode", "Inter", "Noto")
        val families          = names.map(family)
        val sink              = RecordingSink()
        val downloads         = names.map(downloadUrl).toSet
        val canned            = InMemoryHttpClient(routes(noManifest, names*))
        val inFlight          = AtomicInteger(0)
        val release           = CountDownLatch(1)
        // Bodies elsewhere in this suite are four-byte zips that complete in microseconds, so the workers
        // rarely overlap and the "unsynchronised RecordingSink shows corruption" invariant is barely
        // exercised. Gating every download behind one latch, opened only once all four workers have reached
        // it, forces them to finish together instead.
        val gated: HttpClient = new HttpClient:
          def get(
              request: HttpRequest,
              limit: ByteLimit,
              overflow: io.worxbend.nerdfonts.http.Overflow = io.worxbend.nerdfonts.http.Overflow.Reject,
          ): ZIO[Scope, HttpError, HttpResponse] =
            if downloads.contains(request.url) then
              ZIO.succeed(inFlight.incrementAndGet()) *>
                ZIO.attemptBlockingInterrupt(release.await()).orDie *>
                canned.get(request, limit, overflow)
            else canned.get(request, limit, overflow)
        for
          fiber   <- installer(ws, gated).install(request(root, families), sink).fork
          _       <- awaitCondition("four downloads to be in flight"):
                       inFlight.get() >= math.min(FontInstaller.maxConcurrentInstalls, names.size)
          _       <- ZIO.succeed(release.countDown())
          outcome <- fiber.join.either.timeout(zio.Duration.fromScala(5.seconds))
        yield
          val perFamily = families.forall { name =>
            val started   = sink.events.indexOf(
              InstallEvent.Started(name, ReleaseUrls.github.download(ReleaseSelector.Latest, name)),
            )
            val installed = sink.events.indexOf(InstallEvent.Installed(name, root / name.value))
            os.read(root / name.value / s"${name.value}.ttf") == "font" && started >= 0 && installed > started
          }
          eagerly(
            assertTrue(
              outcome == Some(Right(())),
              perFamily,
              sink.events.size == 1 + 2 * names.size,
              sink.events.distinct.size == sink.events.size,
            ),
          )
      }
    ,
    test("one failing family fails the run, names the family, and leaves the finished family installed"):
      withWorkspace { ws =>
        val root     = ws / "fonts"
        val inter    = family("Inter")
        val hackDone = CountDownLatch(1)
        val canned   =
          InMemoryHttpClient(routes(noManifest, "Hack") + (downloadUrl("Inter") -> Response.status(404)))
        val http     = GatedHttpClient(canned, Map(downloadUrl("Inter") -> hackDone))
        val sink     = RecordingSink(event =>
          if event == InstallEvent.Installed(hack, root / "Hack") then hackDone.countDown(),
        )
        installer(ws, http).install(request(root, Vector(hack, inter)), sink).either.map { result =>
          eagerly(
            assertTrue(
              result == Left(
                InstallError.Family(
                  inter,
                  FamilyInstallError.Download(
                    ReleaseUrls.github.download(ReleaseSelector.Latest, inter),
                    HttpError.Status(404),
                  ),
                ),
              ),
              rendered(result) == Left(
                "install Nerd Font family Inter: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Inter.zip: 404 Not Found",
              ),
              os.read(root / "Hack" / "Hack.ttf") == "font",
              !os.exists(root / "Inter"),
            ),
          )
        }
      }
    ,
    test("a failing sibling interrupts a still-downloading family, which cleans up and is not installed"):
      withWorkspace { ws =>
        val root  = ws / "fonts"
        val inter = family("Inter")
        val http  = InMemoryHttpClient(
          Map(
            manifestUrl          -> noManifest,
            downloadUrl("Hack")  -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
            downloadUrl("Inter") -> Response.status(404),
          ),
        )
        installer(ws, http)
          .install(request(root, Vector(hack, inter)), RecordingSink())
          .either
          .timeout(zio.Duration.fromScala(5.seconds))
          .map { outcome =>
            eagerly(
              assertTrue(
                outcome == Some(
                  Left(
                    InstallError.Family(
                      inter,
                      FamilyInstallError.Download(
                        ReleaseUrls.github.download(ReleaseSelector.Latest, inter),
                        HttpError.Status(404),
                      ),
                    ),
                  ),
                ),
                tempZips(ws).isEmpty,
                stagingDirs(root).isEmpty,
                !os.exists(root / "Hack"),
              ),
            )
          }
      }
    ,
    test("keeps the existing family directory when extraction fails"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        ZIO.attemptBlockingIO(os.write(root / "Hack" / "old.ttf", "old", createFolders = true)) *> {
          val http = InMemoryHttpClient(
            Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.ok(FontZips.noFonts)),
          )
          installer(ws, http).install(request(root, Vector(hack)), RecordingSink()).either.map { result =>
            eagerly(
              assertTrue(
                rendered(result).left.exists(_.endsWith(": no font files found")),
                os.read(root / "Hack" / "old.ttf") == "old",
                stagingDirs(root).isEmpty,
                tempZips(ws).isEmpty,
              ),
            )
          }
        }
      }
    ,
    test("replaces an existing family directory after a successful extraction"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        ZIO.attemptBlockingIO(os.write(root / "Hack" / "old.ttf", "old", createFolders = true)) *>
          installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")))
            .install(request(root, Vector(hack)), RecordingSink())
            .either
            .map { result =>
              eagerly(
                assertTrue(
                  result == Right(()),
                  os.list(root / "Hack").map(_.last) == Seq("Hack.ttf"),
                  !os.exists(root / "Hack.old"),
                ),
              )
            }
      }
    ,
    test("a matching checksum installs without a warning"):
      withWorkspace { ws =>
        val root     = ws / "fonts"
        val zip      = FontZips.family("Hack")
        val manifest = Response.ok(s"${FontZips.digest(zip).value}  Hack.zip\n")
        val sink     = RecordingSink()
        val http     = InMemoryHttpClient(Map(manifestUrl -> manifest, downloadUrl("Hack") -> Response.ok(zip)))
        installer(ws, http).install(request(root, Vector(hack)), sink).either.map { result =>
          eagerly(
            assertTrue(
              result == Right(()),
              os.exists(root / "Hack" / "Hack.ttf"),
              !sink.events.exists(_.isInstanceOf[InstallEvent.ChecksumManifestUnavailable]),
            ),
          )
        }
      }
    ,
    test("a checksum mismatch is fatal, names the family twice, and installs nothing"):
      withWorkspace { ws =>
        val root     = ws / "fonts"
        val zip      = FontZips.family("Hack")
        val manifest = Response.ok(s"${"a" * 64}  Hack.zip\n")
        val http     = InMemoryHttpClient(Map(manifestUrl -> manifest, downloadUrl("Hack") -> Response.ok(zip)))
        installer(ws, http).install(request(root, Vector(hack)), RecordingSink()).either.map { result =>
          eagerly(
            assertTrue(
              rendered(result) == Left(
                s"install Nerd Font family Hack: checksum mismatch for Hack: downloaded sha256 ${FontZips.digest(zip).value}, expected ${"a" * 64}",
              ),
              !os.exists(root / "Hack"),
              tempZips(ws).isEmpty,
            ),
          )
        }
      }
    ,
    test("a missing manifest warns with the status line and proceeds"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val sink = RecordingSink()
        val http = InMemoryHttpClient(routes(Response.status(403), "Hack"))
        installer(ws, http).install(request(root, Vector(hack)), sink).either.map { result =>
          eagerly(
            assertTrue(
              result == Right(()),
              sink.events.headOption == Some(InstallEvent.ChecksumManifestUnavailable("403 Forbidden")),
              os.exists(root / "Hack" / "Hack.ttf"),
            ),
          )
        }
      }
    ,
    test("a manifest reset mid-stream warns with its cause and installs anyway"):
      withWorkspace { ws =>
        val root         = ws / "fonts"
        val sink         = RecordingSink()
        val resetPartway = Body.failingAfter(2, "Connection reset", okByte = 'a'.toInt)
        val http         = InMemoryHttpClient(
          Map(
            manifestUrl         -> Response.Served(200, Map.empty, resetPartway),
            downloadUrl("Hack") -> Response.ok(FontZips.family("Hack")),
          ),
        )
        installer(ws, http).install(request(root, Vector(hack)), sink).either.map { result =>
          eagerly(
            assertTrue(
              result == Right(()),
              sink.events.headOption == Some(InstallEvent.ChecksumManifestUnavailable("Connection reset")),
              os.exists(root / "Hack" / "Hack.ttf"),
            ),
          )
        }
      }
    ,
    test("a manifest transport failure warns with its cause"):
      withWorkspace { ws =>
        val sink = RecordingSink()
        val http = InMemoryHttpClient(routes(Response.transport("dial tcp: connection refused"), "Hack"))
        installer(ws, http).install(request(ws / "fonts", Vector(hack)), sink).either.map { result =>
          eagerly(
            assertTrue(
              result == Right(()),
              sink.events.headOption == Some(
                InstallEvent.ChecksumManifestUnavailable("dial tcp: connection refused"),
              ),
            ),
          )
        }
      }
    ,
    test("a truncated manifest keeps the digests parsed before the cut"):
      withWorkspace { ws =>
        val firstLine = s"${"a" * 64}  Hack.zip\n"
        val manifest  = Response.ok(firstLine + s"${"b" * 64}  JetBrainsMono.zip\n")
        val limits    = SizeLimits(manifest = ByteLimit.bytes(firstLine.length.toLong + 20))
        val sink      = RecordingSink()
        val http      = InMemoryHttpClient(
          Map(manifestUrl -> manifest, downloadUrl("Hack") -> Response.ok(FontZips.family("Hack"))),
        )
        installer(ws, http, limits).install(request(ws / "fonts", Vector(hack)), sink).either.map { result =>
          eagerly(
            assertTrue(
              rendered(result).left.exists(_.contains("checksum mismatch for Hack")),
              !sink.events.exists(_.isInstanceOf[InstallEvent.ChecksumManifestUnavailable]),
            ),
          )
        }
      }
    ,
    test("an oversize Content-Length is rejected with the declared size"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val http = InMemoryHttpClient(
          Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.okDeclaring(9999999L, Array.empty)),
        )
        installer(ws, http, SizeLimits(download = ByteLimit.bytes(10)))
          .install(request(root, Vector(hack)), RecordingSink())
          .either
          .map { result =>
            eagerly(
              assertTrue(
                rendered(result) == Left(
                  s"install Nerd Font family Hack: download ${downloadUrl("Hack").value}: size 9999999 bytes exceeds 10 byte limit",
                ),
                !os.exists(root / "Hack"),
              ),
            )
          }
      }
    ,
    test("an oversize stream is rejected with the bare limit"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val http = InMemoryHttpClient(routes(noManifest, "Hack"))
        installer(ws, http, SizeLimits(download = ByteLimit.bytes(10)))
          .install(request(root, Vector(hack)), RecordingSink())
          .either
          .map { result =>
            eagerly(
              assertTrue(
                rendered(result) == Left(
                  s"install Nerd Font family Hack: download ${downloadUrl("Hack").value}: exceeds 10 byte limit",
                ),
                !os.exists(root / "Hack"),
                tempZips(ws).isEmpty,
              ),
            )
          }
      }
    ,
    test("a transport failure renders download <url>: <cause> and leaves no family directory"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val http = InMemoryHttpClient(
          Map(
            manifestUrl         -> noManifest,
            downloadUrl("Hack") -> Response.transport("simulated network failure"),
          ),
        )
        installer(ws, http).install(request(root, Vector(hack)), RecordingSink()).either.map { result =>
          eagerly(
            assertTrue(
              rendered(result) == Left(
                s"install Nerd Font family Hack: download ${downloadUrl("Hack").value}: simulated network failure",
              ),
              !os.exists(root / "Hack"),
            ),
          )
        }
      }
    ,
    test("a body that fails mid-stream is a copy failure naming the temp zip"):
      withWorkspace { ws =>
        val root    = ws / "fonts"
        val failing = Body.Streamed(() =>
          new InputStream:
            override def read(): Int = throw IOException("simulated network failure"),
        )
        val http    = InMemoryHttpClient(
          Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.Served(200, Map.empty, failing)),
        )
        installer(ws, http).install(request(root, Vector(hack)), RecordingSink()).either.map { result =>
          val message =
            rendered(result).left.getOrElse(throw AssertionError(s"expected a failure, got $result"))
          eagerly(
            assertTrue(
              message.startsWith(
                s"install Nerd Font family Hack: copy download ${downloadUrl("Hack").value} to ${ws / "tmp"}/nerd-font-",
              ),
              message.endsWith(".zip: simulated network failure"),
              tempZips(ws).isEmpty,
            ),
          )
        }
      }
    ,
    test("a temp directory that does not exist fails with create temporary zip file"):
      withWorkspace { ws =>
        val http   = InMemoryHttpClient(routes(noManifest, "Hack"))
        val engine = FontInstaller(http, ws / "missing-tmp", noFcCache)
        engine.install(request(ws / "fonts", Vector(hack)), RecordingSink()).either.map { result =>
          eagerly(
            assertTrue(
              rendered(result).left
                .exists(_.startsWith("install Nerd Font family Hack: create temporary zip file: ")),
            ),
          )
        }
      }
    ,
    test("duplicate families collapse into one download"):
      withWorkspace { ws =>
        val http = InMemoryHttpClient(routes(noManifest, "Hack"))
        installer(ws, http)
          .install(request(ws / "fonts", Vector(hack, hack, hack)), RecordingSink())
          .either
          .map { result =>
            eagerly(
              assertTrue(result == Right(()), http.requests.map(_.url).count(_ == downloadUrl("Hack")) == 1),
            )
          }
      }
    ,
    test("a missing fc-cache warns and succeeds without running anything"):
      withWorkspace { ws =>
        val root   = ws / "fonts"
        val runner = FakeProcessRunner()
        val sink   = RecordingSink()
        val req    = request(root, Vector(hack), refresh = RefreshFontCache.Enabled)
        installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")), refresher = FcCacheRefresher(runner))
          .install(req, sink)
          .either
          .map { result =>
            eagerly(
              assertTrue(
                result == Right(()),
                sink.events.lastOption == Some(InstallEvent.FontCacheUnavailable),
                runner.calls == Vector.empty,
              ),
            )
          }
      }
    ,
    test("a successful fc-cache run is bracketed by Refreshing and Refreshed"):
      withWorkspace { ws =>
        val root   = ws / "fonts"
        val runner = FakeProcessRunner(
          Vector(Script.succeeding(Vector("fc-cache"))),
          Map("fc-cache" -> os.Path("/usr/bin/fc-cache")),
        )
        val sink   = RecordingSink()
        val req    = request(root, Vector(hack), refresh = RefreshFontCache.Enabled)
        installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")), refresher = FcCacheRefresher(runner))
          .install(req, sink)
          .either
          .map { result =>
            eagerly(
              assertTrue(
                result == Right(()),
                sink.events.takeRight(2) == Vector(
                  InstallEvent.RefreshingFontCache(root),
                  InstallEvent.FontCacheRefreshed,
                ),
                runner.calls == Vector(ProcessSpec(Vector("fc-cache", "-f", root.toString))),
              ),
            )
          }
      }
    ,
    test("a non-zero fc-cache exit fails the run with run fc-cache"):
      withWorkspace { ws =>
        val root   = ws / "fonts"
        val runner = FakeProcessRunner(
          Vector(Script.exiting(Vector("fc-cache"), 1)),
          Map("fc-cache" -> os.Path("/usr/bin/fc-cache")),
        )
        val req    = request(root, Vector(hack), refresh = RefreshFontCache.Enabled)
        installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")), refresher = FcCacheRefresher(runner))
          .install(req, RecordingSink())
          .either
          .map { result =>
            eagerly(
              assertTrue(
                result == Left(InstallError.FontCache(root, FontCacheError.Exit(ExitStatus.of(1)))),
                rendered(result) == Left(s"run fc-cache for $root: exit status 1"),
              ),
            )
          }
      }
    ,
    test(
      "interrupting the installing thread mid-download cleans up, keeps the old fonts, and does not deadlock",
    ):
      withWorkspace { ws =>
        val root = ws / "fonts"
        ZIO.attemptBlockingIO(os.write(root / "Hack" / "old.ttf", "old", createFolders = true)) *> {
          val http   = InMemoryHttpClient(
            Map(
              manifestUrl         -> noManifest,
              downloadUrl("Hack") -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
            ),
          )
          val engine = installer(ws, http)
          for
            fiber <- engine.install(request(root, Vector(hack)), RecordingSink()).fork
            _     <- awaitCondition("the download to start")(http.requests.exists(_.url == downloadUrl("Hack")))
            exit  <- fiber.interrupt
          yield eagerly(
            assertTrue(
              exit.isInterrupted,
              tempZips(ws).isEmpty,
              stagingDirs(root).isEmpty,
              os.read(root / "Hack" / "old.ttf") == "old",
            ),
          )
        }
      }
    ,
    test("the per-family deadline yields TimedOut with the temp zip removed"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val http = InMemoryHttpClient(
          Map(
            manifestUrl         -> noManifest,
            downloadUrl("Hack") -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
          ),
        )
        installer(ws, http, familyDeadline = 200.millis)
          .install(request(root, Vector(hack)), RecordingSink())
          .either
          .map { result =>
            eagerly(
              assertTrue(
                result == Left(InstallError.Family(hack, FamilyInstallError.TimedOut(200.millis))),
                rendered(result) == Left("install Nerd Font family Hack: timed out after 200 milliseconds"),
                tempZips(ws).isEmpty,
                stagingDirs(root).isEmpty,
              ),
            )
          }
      }
    ,
    test("a stalled manifest fetch warns with the timeout and proceeds"):
      withWorkspace { ws =>
        val root = ws / "fonts"
        val sink = RecordingSink()
        val http = InMemoryHttpClient(
          Map(
            manifestUrl         -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
            downloadUrl("Hack") -> Response.ok(FontZips.family("Hack")),
          ),
        )
        installer(ws, http, manifestTimeout = 100.millis)
          .install(request(root, Vector(hack)), sink)
          .either
          .map { result =>
            eagerly(
              assertTrue(
                result == Right(()),
                sink.events.headOption == Some(
                  InstallEvent.ChecksumManifestUnavailable("timed out after 100 milliseconds"),
                ),
                os.exists(root / "Hack" / "Hack.ttf"),
              ),
            )
          }
      },
  ) @@ TestAspect.withLiveClock

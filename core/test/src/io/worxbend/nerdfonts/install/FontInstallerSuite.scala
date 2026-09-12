package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.InMemoryHttpClient
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Body
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response
import io.worxbend.nerdfonts.http.Overflow
import io.worxbend.nerdfonts.http.Url
import io.worxbend.nerdfonts.process.ExitStatus
import io.worxbend.nerdfonts.process.FakeProcessRunner
import io.worxbend.nerdfonts.process.FakeProcessRunner.Script
import io.worxbend.nerdfonts.process.ProcessSpec
import io.worxbend.nerdfonts.releases.ReleaseUrls

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** Every `core/install` scenario of SPEC §6, through the real engine with in-memory ports. */
final class FontInstallerSuite extends munit.FunSuite:
  private val workspace = FunFixture[os.Path](_ => os.temp.dir(prefix = "font-installer"), os.remove.all(_))

  private val hack        = family("Hack")
  private val manifestUrl = ReleaseUrls.github.checksums(ReleaseSelector.Latest)
  private val noManifest  = Response.status(404)

  private def family(name: String): FamilyName = FamilyName.parse(name).getOrElse(fail(s"unsafe $name"))

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

  @tailrec
  private def awaitCondition(what: String, deadlineNanos: Long)(condition: => Boolean): Unit =
    if condition then ()
    else if System.nanoTime() > deadlineNanos then fail(s"timed out waiting for $what")
    else
      Thread.sleep(10)
      awaitCondition(what, deadlineNanos)(condition)

  workspace.test("a dry run emits the plan and touches neither the network nor the disk"): ws =>
    val root = ws / "fonts"
    val http = InMemoryHttpClient(routes(noManifest, "Hack"))
    val sink = RecordingSink()
    val req  = request(root, Vector(hack), refresh = RefreshFontCache.Enabled, dryRun = DryRun.Enabled)
    assertEquals(installer(ws, http).install(req, sink), Right(()))
    assertEquals(
      sink.events,
      Vector(
        InstallEvent
          .WouldInstall(hack, ReleaseUrls.github.download(ReleaseSelector.Latest, hack), root / "Hack"),
        InstallEvent.WouldRefreshCache(root),
      ),
    )
    assertEquals(http.requests, Vector.empty)
    assert(!os.exists(root))

  workspace.test("a destination that cannot be created fails before any HTTP call"): ws =>
    val blocker = ws / "file"
    os.write(blocker, "not a directory")
    val http    = InMemoryHttpClient(routes(noManifest, "Hack"))
    val result  = installer(ws, http).install(request(blocker / "fonts", Vector(hack)), RecordingSink())
    assert(result.left.exists(_.isInstanceOf[InstallError.Destination]), result.toString)
    assert(rendered(result).left.exists(_.startsWith(s"create destination ${blocker / "fonts"}: ")))
    assertEquals(http.requests, Vector.empty)

  workspace.test("installs a single family into <root>/<Family> and reports Started then Installed"): ws =>
    val root = ws / "fonts"
    val sink = RecordingSink()
    assertEquals(
      installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")))
        .install(request(root, Vector(hack)), sink),
      Right(()),
    )
    assertEquals(os.read(root / "Hack" / "Hack.ttf"), "font")
    assertEquals(
      sink.events,
      Vector(
        InstallEvent.ChecksumManifestUnavailable("404 Not Found"),
        InstallEvent.Started(hack, ReleaseUrls.github.download(ReleaseSelector.Latest, hack)),
        InstallEvent.Installed(hack, root / "Hack"),
      ),
    )
    assertEquals(tempZips(ws), Seq.empty)
    assertEquals(stagingDirs(root), Seq.empty)

  workspace.test("an upper-case .TTF entry is extracted"): ws =>
    val root = ws / "fonts"
    val http = InMemoryHttpClient(
      Map(
        manifestUrl         -> noManifest,
        downloadUrl("Hack") -> Response.ok(FontZips.deflated("Hack.TTF" -> "font")),
      ),
    )
    assertEquals(installer(ws, http).install(request(root, Vector(hack)), RecordingSink()), Right(()))
    assert(os.exists(root / "Hack" / "Hack.TTF"))

  workspace.test(
    "installs many families concurrently with every line intact and each Started before its Installed",
  ): ws =>
    val root              = ws / "fonts"
    val names             = Vector("Hack", "JetBrainsMono", "FiraCode", "Inter", "Noto")
    val families          = names.map(family)
    val sink              = RecordingSink()
    val downloads         = names.map(downloadUrl).toSet
    val canned            = InMemoryHttpClient(routes(noManifest, names*))
    val inFlight          = AtomicInteger(0)
    val release           = CountDownLatch(1)
    // Bodies elsewhere in this suite are four-byte zips that complete in microseconds, so the workers rarely
    // overlap and the "unsynchronised RecordingSink shows corruption" invariant is barely exercised. Gating
    // every download behind one latch, opened only once all four workers have reached it, forces them to
    // finish together instead.
    val gated: HttpClient = new HttpClient:
      def get[A](request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject)(
          consume: InputStream => A,
      ): Either[HttpError, A] =
        if downloads.contains(request.url) then
          inFlight.incrementAndGet()
          release.await()
        canned.get(request, limit, overflow)(consume)
    val outcome           = AtomicReference[Option[Either[InstallError, Unit]]](None)
    val worker            = Thread: () =>
      outcome.set(Some(installer(ws, gated).install(request(root, families), sink)))
    worker.start()
    awaitCondition("four downloads to be in flight", System.nanoTime() + 5.seconds.toNanos):
      inFlight.get() >= math.min(FontInstaller.maxConcurrentInstalls, names.size)
    release.countDown()
    worker.join(5.seconds.toMillis)
    assert(!worker.isAlive, "install did not finish within five seconds of releasing the gate")
    assertEquals(outcome.get(), Some(Right(())))
    families.foreach: name =>
      assertEquals(os.read(root / name.value / s"${name.value}.ttf"), "font")
      val started   = sink.events.indexOf(
        InstallEvent.Started(name, ReleaseUrls.github.download(ReleaseSelector.Latest, name)),
      )
      val installed = sink.events.indexOf(InstallEvent.Installed(name, root / name.value))
      assert(started >= 0 && installed > started, s"$name: started=$started installed=$installed")
    assertEquals(sink.events.size, 1 + 2 * names.size)
    assertEquals(sink.events.distinct.size, sink.events.size)

  workspace.test(
    "one failing family fails the run, names the family, and leaves the finished family installed",
  ): ws =>
    val root     = ws / "fonts"
    val inter    = family("Inter")
    val hackDone = CountDownLatch(1)
    val canned   =
      InMemoryHttpClient(routes(noManifest, "Hack") + (downloadUrl("Inter") -> Response.status(404)))
    val http     = GatedHttpClient(canned, Map(downloadUrl("Inter") -> hackDone))
    val sink     = RecordingSink(event =>
      if event == InstallEvent.Installed(hack, root / "Hack") then hackDone.countDown(),
    )
    val result   = installer(ws, http).install(request(root, Vector(hack, inter)), sink)
    assertEquals(
      result,
      Left(
        InstallError.Family(
          inter,
          FamilyInstallError.Download(
            ReleaseUrls.github.download(ReleaseSelector.Latest, inter),
            HttpError.Status(404),
          ),
        ),
      ),
    )
    assertEquals(
      rendered(result),
      Left(
        "install Nerd Font family Inter: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Inter.zip: 404 Not Found",
      ),
    )
    assertEquals(os.read(root / "Hack" / "Hack.ttf"), "font")
    assert(!os.exists(root / "Inter"))

  workspace.test(
    "a failing sibling interrupts a still-downloading family, which cleans up and is not installed",
  ): ws =>
    val root    = ws / "fonts"
    val inter   = family("Inter")
    val http    = InMemoryHttpClient(
      Map(
        manifestUrl          -> noManifest,
        downloadUrl("Hack")  -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
        downloadUrl("Inter") -> Response.status(404),
      ),
    )
    val outcome = AtomicReference[Option[Either[InstallError, Unit]]](None)
    val worker  = Thread: () =>
      outcome.set(Some(installer(ws, http).install(request(root, Vector(hack, inter)), RecordingSink())))
    worker.start()
    worker.join(5.seconds.toMillis)
    assert(!worker.isAlive, "install did not finish within five seconds of Inter's failure")
    assertEquals(
      outcome.get(),
      Some(
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
    )
    assertEquals(tempZips(ws), Seq.empty)
    assertEquals(stagingDirs(root), Seq.empty)
    assert(!os.exists(root / "Hack"))

  workspace.test("keeps the existing family directory when extraction fails"): ws =>
    val root   = ws / "fonts"
    os.write(root / "Hack" / "old.ttf", "old", createFolders = true)
    val http   =
      InMemoryHttpClient(Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.ok(FontZips.noFonts)))
    val result = installer(ws, http).install(request(root, Vector(hack)), RecordingSink())
    assert(rendered(result).left.exists(_.endsWith(": no font files found")), result.toString)
    assertEquals(os.read(root / "Hack" / "old.ttf"), "old")
    assertEquals(stagingDirs(root), Seq.empty)
    assertEquals(tempZips(ws), Seq.empty)

  workspace.test("replaces an existing family directory after a successful extraction"): ws =>
    val root = ws / "fonts"
    os.write(root / "Hack" / "old.ttf", "old", createFolders = true)
    assertEquals(
      installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")))
        .install(request(root, Vector(hack)), RecordingSink()),
      Right(()),
    )
    assertEquals(os.list(root / "Hack").map(_.last), Seq("Hack.ttf"))
    assert(!os.exists(root / "Hack.old"))

  workspace.test("a matching checksum installs without a warning"): ws =>
    val root     = ws / "fonts"
    val zip      = FontZips.family("Hack")
    val manifest = Response.ok(s"${FontZips.digest(zip).value}  Hack.zip\n")
    val sink     = RecordingSink()
    val http     = InMemoryHttpClient(Map(manifestUrl -> manifest, downloadUrl("Hack") -> Response.ok(zip)))
    assertEquals(installer(ws, http).install(request(root, Vector(hack)), sink), Right(()))
    assert(os.exists(root / "Hack" / "Hack.ttf"))
    assert(!sink.events.exists(_.isInstanceOf[InstallEvent.ChecksumManifestUnavailable]))

  workspace.test("a checksum mismatch is fatal, names the family twice, and installs nothing"): ws =>
    val root     = ws / "fonts"
    val zip      = FontZips.family("Hack")
    val manifest = Response.ok(s"${"a" * 64}  Hack.zip\n")
    val http     = InMemoryHttpClient(Map(manifestUrl -> manifest, downloadUrl("Hack") -> Response.ok(zip)))
    val result   = installer(ws, http).install(request(root, Vector(hack)), RecordingSink())
    assertEquals(
      rendered(result),
      Left(
        s"install Nerd Font family Hack: checksum mismatch for Hack: downloaded sha256 ${FontZips.digest(zip).value}, expected ${"a" * 64}",
      ),
    )
    assert(!os.exists(root / "Hack"))
    assertEquals(tempZips(ws), Seq.empty)

  workspace.test("a missing manifest warns with the status line and proceeds"): ws =>
    val root = ws / "fonts"
    val sink = RecordingSink()
    val http = InMemoryHttpClient(routes(Response.status(403), "Hack"))
    assertEquals(installer(ws, http).install(request(root, Vector(hack)), sink), Right(()))
    assertEquals(sink.events.headOption, Some(InstallEvent.ChecksumManifestUnavailable("403 Forbidden")))
    assert(os.exists(root / "Hack" / "Hack.ttf"))

  workspace.test("a manifest reset mid-stream warns with its cause and installs anyway"): ws =>
    val root         = ws / "fonts"
    val sink         = RecordingSink()
    val resetPartway = Body.failingAfter(2, "Connection reset", okByte = 'a'.toInt)
    val http         = InMemoryHttpClient(
      Map(
        manifestUrl         -> Response.Served(200, Map.empty, resetPartway),
        downloadUrl("Hack") -> Response.ok(FontZips.family("Hack")),
      ),
    )
    assertEquals(installer(ws, http).install(request(root, Vector(hack)), sink), Right(()))
    assertEquals(
      sink.events.headOption,
      Some(InstallEvent.ChecksumManifestUnavailable("Connection reset")),
    )
    assert(os.exists(root / "Hack" / "Hack.ttf"))

  workspace.test("a manifest transport failure warns with its cause"): ws =>
    val sink = RecordingSink()
    val http = InMemoryHttpClient(routes(Response.transport("dial tcp: connection refused"), "Hack"))
    assertEquals(installer(ws, http).install(request(ws / "fonts", Vector(hack)), sink), Right(()))
    assertEquals(
      sink.events.headOption,
      Some(InstallEvent.ChecksumManifestUnavailable("dial tcp: connection refused")),
    )

  workspace.test("a truncated manifest keeps the digests parsed before the cut"): ws =>
    val firstLine = s"${"a" * 64}  Hack.zip\n"
    val manifest  = Response.ok(firstLine + s"${"b" * 64}  JetBrainsMono.zip\n")
    val limits    = SizeLimits(manifest = ByteLimit.bytes(firstLine.length.toLong + 20))
    val sink      = RecordingSink()
    val http      = InMemoryHttpClient(
      Map(manifestUrl -> manifest, downloadUrl("Hack") -> Response.ok(FontZips.family("Hack"))),
    )
    val result    = installer(ws, http, limits).install(request(ws / "fonts", Vector(hack)), sink)
    assert(rendered(result).left.exists(_.contains("checksum mismatch for Hack")), result.toString)
    assert(!sink.events.exists(_.isInstanceOf[InstallEvent.ChecksumManifestUnavailable]))

  workspace.test("an oversize Content-Length is rejected with the declared size"): ws =>
    val root   = ws / "fonts"
    val http   = InMemoryHttpClient(
      Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.okDeclaring(9999999L, Array.empty)),
    )
    val result = installer(ws, http, SizeLimits(download = ByteLimit.bytes(10)))
      .install(request(root, Vector(hack)), RecordingSink())
    assertEquals(
      rendered(result),
      Left(
        s"install Nerd Font family Hack: download ${downloadUrl("Hack").value}: size 9999999 bytes exceeds 10 byte limit",
      ),
    )
    assert(!os.exists(root / "Hack"))

  workspace.test("an oversize stream is rejected with the bare limit"): ws =>
    val root   = ws / "fonts"
    val http   = InMemoryHttpClient(routes(noManifest, "Hack"))
    val result = installer(ws, http, SizeLimits(download = ByteLimit.bytes(10)))
      .install(request(root, Vector(hack)), RecordingSink())
    assertEquals(
      rendered(result),
      Left(s"install Nerd Font family Hack: download ${downloadUrl("Hack").value}: exceeds 10 byte limit"),
    )
    assert(!os.exists(root / "Hack"))
    assertEquals(tempZips(ws), Seq.empty)

  workspace.test("a transport failure renders download <url>: <cause> and leaves no family directory"): ws =>
    val root   = ws / "fonts"
    val http   = InMemoryHttpClient(
      Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.transport("simulated network failure")),
    )
    val result = installer(ws, http).install(request(root, Vector(hack)), RecordingSink())
    assertEquals(
      rendered(result),
      Left(s"install Nerd Font family Hack: download ${downloadUrl("Hack").value}: simulated network failure"),
    )
    assert(!os.exists(root / "Hack"))

  workspace.test("a body that fails mid-stream is a copy failure naming the temp zip"): ws =>
    val root    = ws / "fonts"
    val failing = Body.Streamed(() =>
      new InputStream:
        override def read(): Int = throw IOException("simulated network failure"),
    )
    val http    = InMemoryHttpClient(
      Map(manifestUrl -> noManifest, downloadUrl("Hack") -> Response.Served(200, Map.empty, failing)),
    )
    val result  = installer(ws, http).install(request(root, Vector(hack)), RecordingSink())
    val message = rendered(result).left.getOrElse(fail(s"expected a failure, got $result"))
    assert(
      message.startsWith(
        s"install Nerd Font family Hack: copy download ${downloadUrl("Hack").value} to ${ws / "tmp"}/nerd-font-",
      ),
      message,
    )
    assert(message.endsWith(".zip: simulated network failure"), message)
    assertEquals(tempZips(ws), Seq.empty)

  workspace.test("a temp directory that does not exist fails with create temporary zip file"): ws =>
    val http   = InMemoryHttpClient(routes(noManifest, "Hack"))
    val engine = FontInstaller(http, ws / "missing-tmp", noFcCache)
    val result = engine.install(request(ws / "fonts", Vector(hack)), RecordingSink())
    assert(
      rendered(result).left
        .exists(_.startsWith("install Nerd Font family Hack: create temporary zip file: ")),
      result.toString,
    )

  workspace.test("duplicate families collapse into one download"): ws =>
    val http = InMemoryHttpClient(routes(noManifest, "Hack"))
    assertEquals(
      installer(ws, http).install(request(ws / "fonts", Vector(hack, hack, hack)), RecordingSink()),
      Right(()),
    )
    assertEquals(http.requests.map(_.url).count(_ == downloadUrl("Hack")), 1)

  workspace.test("a missing fc-cache warns and succeeds without running anything"): ws =>
    val root   = ws / "fonts"
    val runner = FakeProcessRunner()
    val sink   = RecordingSink()
    val req    = request(root, Vector(hack), refresh = RefreshFontCache.Enabled)
    assertEquals(
      installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")), refresher = FcCacheRefresher(runner))
        .install(req, sink),
      Right(()),
    )
    assertEquals(sink.events.lastOption, Some(InstallEvent.FontCacheUnavailable))
    assertEquals(runner.calls, Vector.empty)

  workspace.test("a successful fc-cache run is bracketed by Refreshing and Refreshed"): ws =>
    val root   = ws / "fonts"
    val runner = FakeProcessRunner(
      Vector(Script.succeeding(Vector("fc-cache"))),
      Map("fc-cache" -> os.Path("/usr/bin/fc-cache")),
    )
    val sink   = RecordingSink()
    val req    = request(root, Vector(hack), refresh = RefreshFontCache.Enabled)
    assertEquals(
      installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")), refresher = FcCacheRefresher(runner))
        .install(req, sink),
      Right(()),
    )
    assertEquals(
      sink.events.takeRight(2),
      Vector(InstallEvent.RefreshingFontCache(root), InstallEvent.FontCacheRefreshed),
    )
    assertEquals(runner.calls, Vector(ProcessSpec(Vector("fc-cache", "-f", root.toString))))

  workspace.test("a non-zero fc-cache exit fails the run with run fc-cache"): ws =>
    val root   = ws / "fonts"
    val runner = FakeProcessRunner(
      Vector(Script.exiting(Vector("fc-cache"), 1)),
      Map("fc-cache" -> os.Path("/usr/bin/fc-cache")),
    )
    val req    = request(root, Vector(hack), refresh = RefreshFontCache.Enabled)
    val result =
      installer(ws, InMemoryHttpClient(routes(noManifest, "Hack")), refresher = FcCacheRefresher(runner))
        .install(req, RecordingSink())
    assertEquals(result, Left(InstallError.FontCache(root, FontCacheError.Exit(ExitStatus.of(1)))))
    assertEquals(rendered(result), Left(s"run fc-cache for $root: exit status 1"))

  workspace.test(
    "interrupting the installing thread mid-download cleans up, keeps the old fonts, and does not deadlock",
  ): ws =>
    val root    = ws / "fonts"
    os.write(root / "Hack" / "old.ttf", "old", createFolders = true)
    val http    = InMemoryHttpClient(
      Map(
        manifestUrl         -> noManifest,
        downloadUrl("Hack") -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
      ),
    )
    val engine  = installer(ws, http)
    val outcome = AtomicReference[Option[Either[InterruptedException, Either[InstallError, Unit]]]](None)
    val worker  = Thread: () =>
      val result =
        try Right(engine.install(request(root, Vector(hack)), RecordingSink()))
        catch case interrupted: InterruptedException => Left(interrupted)
      outcome.set(Some(result))
    worker.start()
    awaitCondition("the download to start", System.nanoTime() + 5.seconds.toNanos):
      http.requests.exists(_.url == downloadUrl("Hack"))
    worker.interrupt()
    worker.join(5.seconds.toMillis)
    assert(!worker.isAlive, "install did not finish within five seconds of the interrupt")
    assert(
      outcome.get().exists(_.isLeft),
      s"expected the InterruptedException to propagate, got ${outcome.get()}",
    )
    assertEquals(tempZips(ws), Seq.empty)
    assertEquals(stagingDirs(root), Seq.empty)
    assertEquals(os.read(root / "Hack" / "old.ttf"), "old")

  workspace.test("the per-family deadline yields TimedOut with the temp zip removed"): ws =>
    val root   = ws / "fonts"
    val http   = InMemoryHttpClient(
      Map(
        manifestUrl         -> noManifest,
        downloadUrl("Hack") -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
      ),
    )
    val result =
      installer(ws, http, familyDeadline = 200.millis).install(request(root, Vector(hack)), RecordingSink())
    assertEquals(result, Left(InstallError.Family(hack, FamilyInstallError.TimedOut(200.millis))))
    assertEquals(rendered(result), Left("install Nerd Font family Hack: timed out after 200 milliseconds"))
    assertEquals(tempZips(ws), Seq.empty)
    assertEquals(stagingDirs(root), Seq.empty)

  workspace.test("a stalled manifest fetch warns with the timeout and proceeds"): ws =>
    val root = ws / "fonts"
    val sink = RecordingSink()
    val http = InMemoryHttpClient(
      Map(
        manifestUrl         -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted),
        downloadUrl("Hack") -> Response.ok(FontZips.family("Hack")),
      ),
    )
    assertEquals(
      installer(ws, http, manifestTimeout = 100.millis).install(request(root, Vector(hack)), sink),
      Right(()),
    )
    assertEquals(
      sink.events.headOption,
      Some(InstallEvent.ChecksumManifestUnavailable("timed out after 100 milliseconds")),
    )
    assert(os.exists(root / "Hack" / "Hack.ttf"))

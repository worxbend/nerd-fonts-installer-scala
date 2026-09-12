package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpClient.getString
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.Overflow
import io.worxbend.nerdfonts.releases.ChecksumManifest
import io.worxbend.nerdfonts.releases.DownloadUrl
import io.worxbend.nerdfonts.releases.ReleaseUrls
import io.worxbend.nerdfonts.releases.Sha256Digest

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import zio.Duration
import zio.IO
import zio.Semaphore
import zio.UIO
import zio.ZIO
import zio.stream.ZStream

/**
 * The install engine: download, verify, extract and atomically install each family, up to four at a time.
 *
 * Depends only on ports and values (`HttpClient`, `FontCacheRefresher`, `SizeLimits`, a temp directory), never
 * on the environment, so a run is reproducible from its `InstallRequest`. Everything it says goes through the
 * caller's `InstallEventSink`; everything that can fail comes back as an `InstallError`. Per-family paths are
 * disjoint (`<root>/<Family>`, its own staging directory, its own `.old`), which is what makes the fan-out
 * safe; the Go `errgroup` semantics — first failure cancels in-flight siblings, finished families stay
 * installed — are reproduced with `ZIO.foreachPar`, whose own first-failure-interrupts-the-rest behaviour
 * needs no extra signal of our own (§6.5).
 */
final class FontInstaller(
    http: HttpClient,
    tempDir: os.Path,
    refresher: FontCacheRefresher,
    limits: SizeLimits = SizeLimits.default,
    familyDeadline: FiniteDuration = FontInstaller.defaultFamilyDeadline,
    manifestTimeout: FiniteDuration = FontInstaller.defaultManifestTimeout,
    urls: ReleaseUrls = ReleaseUrls.github,
):

  /** The plan a run follows; pure, so a dry run and the real run cannot disagree. */
  def plan(request: InstallRequest): InstallPlan = InstallPlan.of(request, urls)

  def install(request: InstallRequest, sink: InstallEventSink): IO[InstallError, Unit] =
    val planned = plan(request)
    request.dryRun match
      case DryRun.Enabled  => describe(planned, request, sink)
      case DryRun.Disabled => execute(planned, request, sink)

  private def describe(plan: InstallPlan, request: InstallRequest, sink: InstallEventSink): UIO[Unit] =
    ZIO.foreachDiscard(plan.families)(family =>
      ZIO.succeed(sink.emit(InstallEvent.WouldInstall(family.name, family.url, family.targetDir))),
    ) *> (request.refreshFontCache match
      case RefreshFontCache.Enabled  => ZIO.succeed(sink.emit(InstallEvent.WouldRefreshCache(request.root)))
      case RefreshFontCache.Disabled => ZIO.unit)

  // One semaphore per run serialises every `sink.emit`, replacing the Ox `Actor` that used to give the same
  // guarantee: `InstallEventSink` promises one caller at a time, and the family fan-out below is the only
  // place several fibers could call it at once.
  private def execute(
      plan: InstallPlan,
      request: InstallRequest,
      sink: InstallEventSink,
  ): IO[InstallError, Unit] =
    for
      semaphore <- Semaphore.make(1)
      emit       = (event: InstallEvent) => semaphore.withPermit(ZIO.succeed(sink.emit(event)))
      _         <- createRoot(request.root)
      digests   <- fetchDigests(request.selector, emit)
      _         <- installAll(plan, request.root, digests, emit)
      _         <- refreshCache(request, emit)
    yield ()

  private def createRoot(root: os.Path): IO[InstallError, Unit] = ZIO
    .attemptBlockingIO(os.makeDir.all(root))
    .mapError(error => InstallError.Destination(root, Diagnostics.describe(error)))

  // Verification is best-effort: an unavailable manifest is a warning and every family installs unverified;
  // only a digest that is present and differs is fatal. `Truncate` keeps the first MiB like Go's LimitReader.
  private def fetchDigests(
      selector: ReleaseSelector,
      emit: InstallEvent => UIO[Unit],
  ): UIO[Map[FamilyName, Sha256Digest]] =
    val request  = HttpRequest(urls.checksums(selector))
    val timedOut = HttpError.Transport(s"timed out after $manifestTimeout")
    http
      .getString(request, limits.manifest, Overflow.Truncate)
      .timeoutFail(timedOut)(Duration.fromScala(manifestTimeout))
      .foldZIO(
        error => emit(InstallEvent.ChecksumManifestUnavailable(error.render)).as(Map.empty),
        text => ZIO.succeed(ChecksumManifest.parse(text)),
      )

  private def installAll(
      plan: InstallPlan,
      root: os.Path,
      digests: Map[FamilyName, Sha256Digest],
      emit: InstallEvent => UIO[Unit],
  ): IO[InstallError, Unit] = runFanOut(plan, root, digests, emit)

  private def runFanOut(
      plan: InstallPlan,
      root: os.Path,
      digests: Map[FamilyName, Sha256Digest],
      emit: InstallEvent => UIO[Unit],
  ): IO[InstallError, Unit] = ZIO
    .foreachPar(plan.families)(family =>
      installFamily(family, root, digests.get(family.name), emit)
        .mapError(InstallError.Family(family.name, _)),
    )
    .withParallelism(parallelism(plan))
    .unit

  private def parallelism(plan: InstallPlan): Int =
    math.max(1, math.min(FontInstaller.maxConcurrentInstalls, plan.families.size))

  // The deadline wraps `fetchAndStage` from *inside* `withTempZip`'s `use`, not the whole family install: ZIO's
  // `timeoutFail` is built on `raceFibersWith`, which forks the guarded effect onto its own child fiber and
  // waits on it via a plain (non-cancellable) `ZIO.async`. When that composite is itself externally
  // interrupted — as a failing sibling family does via `foreachPar` — the *outer* fiber's own suspension in
  // that `async` is abandoned early, before the forked child (here, the family's download-and-extract) has
  // actually finished unwinding. If `withTempZip`'s `.onExit` cleanup were *inside* the timeout (wrapping
  // `installFamilyNow` as a whole, as an initial pass at this migration had it), that early abandonment would
  // let the temp zip's removal race the reported failure, occasionally losing: the fiber reporting to
  // `foreachPar` would return before its own finalizer ran. Keeping `withTempZip` (and `withStaging`, one
  // level deeper, which never touches a stream so never hits this) *outside* `timeoutFail` means the fiber
  // `foreachPar` actually interrupts is the one still directly running the cleanup, not a detached child of
  // the race — so `Fiber#interrupt`'s wait covers it correctly. Confirmed with a standalone repro of
  // `ZStream.fromInputStreamZIO` interrupted underneath `timeoutFail` inside `foreachPar` before settling on
  // this shape.
  private def installFamily(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      emit: InstallEvent => UIO[Unit],
  ): IO[FamilyInstallError, Unit] = installFamilyNow(planned, root, expected, emit)

  private def installFamilyNow(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      emit: InstallEvent => UIO[Unit],
  ): IO[FamilyInstallError, Unit] =
    for
      _ <- emit(InstallEvent.Started(planned.name, planned.url))
      _ <- withTempZip(zip =>
             fetchAndStage(planned, root, expected, zip)
               .timeoutFail(FamilyInstallError.TimedOut(familyDeadline))(Duration.fromScala(familyDeadline)),
           )
      _ <- emit(InstallEvent.Installed(planned.name, planned.targetDir))
    yield ()

  private def fetchAndStage(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      zip: os.Path,
  ): IO[FamilyInstallError, Unit] =
    for
      actual <- download(planned.url, zip)
      _      <- verify(actual, expected)
      _      <- withStaging(root, planned.name)(staging => extractAndSwap(zip, staging, planned.targetDir))
    yield ()

  private def extractAndSwap(zip: os.Path, staging: os.Path, target: os.Path): IO[FamilyInstallError, Unit] =
    for
      _ <- ArchiveExtractor
             .extract(zip, staging, limits)
             .mapError(FamilyInstallError.Extraction(zip, staging, _))
      _ <- DirectorySwap.replace(staging, target).mapError(FamilyInstallError.Swap(_))
    yield ()

  // Both size checks are the port's; only `HttpError.TooLarge` surfacing while the body is drained is still a
  // download failure, matching the `get` call's own oversize-`Content-Length` rejection. Any other failure
  // seen while writing the response — a transport reset mid-stream, or a local `IOException` — is a copy
  // failure, because headers were already accepted by the time it happened.
  private def download(url: DownloadUrl, zip: os.Path): IO[FamilyInstallError, Sha256Digest] = ZIO.scoped:
    http
      .get(HttpRequest(url.url), limits.download)
      .mapError(FamilyInstallError.Download(url, _))
      .flatMap(response =>
        copyHashing(response.body, zip).mapError:
          case error: HttpError.TooLarge => FamilyInstallError.Download(url, error)
          case error: HttpError          => FamilyInstallError.Copy(url, zip, error.render)
          case error: IOException        => FamilyInstallError.Copy(url, zip, Diagnostics.describe(error)),
      )

  // Hashed while copied so the archive is read once and never held in memory. `NOFOLLOW_LINKS`:
  // `createTempZip` created this exact path moments ago, but a shared, non-sticky `$TMPDIR` still leaves a
  // window for another local user to swap in a symlink before this open; refusing to follow it turns a
  // symlink clobber into a loud failure instead of overwriting whatever it points to. The file handle is
  // closed on the blocking pool regardless of outcome; a close failure that survives a successful write is
  // rare enough (a just-created temp file on a healthy filesystem) that it is treated as a defect rather than
  // wired into `FamilyInstallError`, which mirrors how a ZIO finalizer can never itself fail the effect it
  // guards.
  private def copyHashing(
      body: ZStream[Any, HttpError, Byte],
      zip: os.Path,
  ): IO[HttpError | IOException, Sha256Digest] =
    val digest  = MessageDigest.getInstance("SHA-256")
    val options = Array[OpenOption](
      StandardOpenOption.WRITE,
      StandardOpenOption.TRUNCATE_EXISTING,
      LinkOption.NOFOLLOW_LINKS,
    )
    ZIO.acquireReleaseWith(ZIO.attemptBlockingIO(Files.newOutputStream(zip.toNIO, options*)))(out =>
      ZIO.attemptBlockingIO(out.close()).orDie,
    ): out =>
      body
        .runForeachChunk(chunk =>
          ZIO.attemptBlockingIO {
            val bytes = chunk.toArray
            digest.update(bytes)
            out.write(bytes)
          }: ZIO[Any, HttpError | IOException, Unit],
        )
        .as(Sha256Digest.fromBytes(digest.digest()))

  private def verify(actual: Sha256Digest, expected: Option[Sha256Digest]): IO[FamilyInstallError, Unit] =
    expected match
      case Some(wanted) if wanted != actual => ZIO.fail(FamilyInstallError.ChecksumMismatch(actual, wanted))
      case _                                => ZIO.unit

  // The temp zip and the staging directory are removed once `use` completes, fails, or is interrupted, so a
  // deadline (§6.8) or a failing sibling leaves nothing behind while an existing `<root>/<Family>` stays
  // untouched until the swap. `acquireReleaseWith` (rather than `flatMap` + `ensuring`) is load-bearing: it
  // makes the acquire uninterruptible and registers the finalizer atomically with its success. With
  // `ensuring` the finalizer is only installed once the continuation is entered, so an interrupt delivered
  // while the blocking `os.temp` call is in flight would leave the file or directory on disk with nothing
  // registered to remove it.
  private def withTempZip(use: os.Path => IO[FamilyInstallError, Unit]): IO[FamilyInstallError, Unit] =
    ZIO.acquireReleaseWith(createTempZip())(Cleanup.removeFile)(use)

  private def createTempZip(): IO[FamilyInstallError, os.Path] = ZIO
    .attemptBlockingIO(
      os.temp(
        dir = tempDir,
        prefix = FontInstaller.tempZipPrefix,
        suffix = FontInstaller.tempZipSuffix,
        deleteOnExit = false,
      ),
    )
    .mapError(error => FamilyInstallError.TempZip(Diagnostics.describe(error)))

  private def withStaging(root: os.Path, family: FamilyName)(
      use: os.Path => IO[FamilyInstallError, Unit],
  ): IO[FamilyInstallError, Unit] =
    ZIO.acquireReleaseWith(createStaging(root, family))(Cleanup.removeTree)(use)

  // Staged inside `root` so the final rename is a same-filesystem `rename(2)`; the dot prefix hides it from
  // font tooling that scans the directory while an install is in flight.
  private def createStaging(root: os.Path, family: FamilyName): IO[FamilyInstallError, os.Path] = ZIO
    .attemptBlockingIO(os.temp.dir(dir = root, prefix = s".${family.value}-", deleteOnExit = false))
    .mapError(error => FamilyInstallError.Staging(root, Diagnostics.describe(error)))

  private def refreshCache(request: InstallRequest, emit: InstallEvent => UIO[Unit]): IO[InstallError, Unit] =
    request.refreshFontCache match
      case RefreshFontCache.Disabled => ZIO.unit
      case RefreshFontCache.Enabled  => refresher.availability.flatMap:
          case FontCacheAvailability.Unavailable => emit(InstallEvent.FontCacheUnavailable)
          case FontCacheAvailability.Available   => runFontCache(request.root, emit)

  private def runFontCache(root: os.Path, emit: InstallEvent => UIO[Unit]): IO[InstallError, Unit] =
    for
      _ <- emit(InstallEvent.RefreshingFontCache(root))
      _ <- refresher.refresh(root).mapError(InstallError.FontCache(root, _))
      _ <- emit(InstallEvent.FontCacheRefreshed)
    yield ()

object FontInstaller:
  /** Downloads all target one host and are bandwidth bound; a small fan-out captures most of the speedup. */
  val maxConcurrentInstalls: Int = 4

  /** Bounds one family's download plus extraction; composes with sibling cancellation instead of a global clock. */
  val defaultFamilyDeadline: FiniteDuration = 10.minutes

  /** Stricter than Go's unbounded manifest fetch, acceptable because a manifest failure is only a warning. */
  val defaultManifestTimeout: FiniteDuration = 30.seconds

  val tempZipPrefix: String = "nerd-font-"
  val tempZipSuffix: String = ".zip"

package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.Overflow
import io.worxbend.nerdfonts.releases.ChecksumManifest
import io.worxbend.nerdfonts.releases.DownloadUrl
import io.worxbend.nerdfonts.releases.ReleaseUrls
import io.worxbend.nerdfonts.releases.Sha256Digest

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Using
import scala.util.control.NoStackTrace

import ox.channels.Actor
import ox.discard
import ox.either
import ox.either.catching
import ox.either.ok
import ox.flow.Flow
import ox.supervised
import ox.timeoutEither

/**
 * The install engine: download, verify, extract and atomically install each family, up to four at a time.
 *
 * Depends only on ports and values (`HttpClient`, `FontCacheRefresher`, `SizeLimits`, a temp directory), never
 * on the environment, so a run is reproducible from its `InstallRequest`. Everything it says goes through the
 * caller's `InstallEventSink`; everything that can fail comes back as an `InstallError`. Per-family paths are
 * disjoint (`<root>/<Family>`, its own staging directory, its own `.old`), which is what makes the fan-out
 * safe; the Go `errgroup` semantics — first failure cancels in-flight siblings, finished families stay
 * installed — are reproduced with the private [[FamilyInstallAborted]] signal of §6.5.
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

  def install(request: InstallRequest, sink: InstallEventSink): Either[InstallError, Unit] =
    val planned = plan(request)
    request.dryRun match
      case DryRun.Enabled  => Right(describe(planned, request, sink))
      case DryRun.Disabled => execute(planned, request, sink)

  private def describe(plan: InstallPlan, request: InstallRequest, sink: InstallEventSink): Unit =
    plan.families.foreach(family =>
      sink.emit(InstallEvent.WouldInstall(family.name, family.url, family.targetDir)),
    )
    request.refreshFontCache match
      case RefreshFontCache.Enabled  => sink.emit(InstallEvent.WouldRefreshCache(request.root))
      case RefreshFontCache.Disabled => ()

  private def execute(
      plan: InstallPlan,
      request: InstallRequest,
      sink: InstallEventSink,
  ): Either[InstallError, Unit] = either:
    createRoot(request.root).ok()
    val digests = fetchDigests(request.selector, sink)
    installAll(plan, request.root, digests, sink).ok()
    refreshCache(request, sink).ok()

  private def createRoot(root: os.Path): Either[InstallError, Unit] = os.makeDir
    .all(root)
    .catching[IOException]
    .left
    .map(error => InstallError.Destination(root, Diagnostics.describe(error)))

  // Verification is best-effort: an unavailable manifest is a warning and every family installs unverified;
  // only a digest that is present and differs is fatal. `Truncate` keeps the first MiB like Go's LimitReader.
  private def fetchDigests(selector: ReleaseSelector, sink: InstallEventSink): Map[FamilyName, Sha256Digest] =
    val request  = HttpRequest(urls.checksums(selector))
    val timedOut = HttpError.Transport(s"timed out after $manifestTimeout")
    timeoutEither(manifestTimeout, timedOut)(
      http.getString(request, limits.manifest, Overflow.Truncate),
    ) match
      case Right(text) => ChecksumManifest.parse(text)
      case Left(error) =>
        sink.emit(InstallEvent.ChecksumManifestUnavailable(error.render))
        Map.empty

  private def installAll(
      plan: InstallPlan,
      root: os.Path,
      digests: Map[FamilyName, Sha256Digest],
      sink: InstallEventSink,
  ): Either[InstallError, Unit] = runFanOut(plan, root, digests, sink)
    .catching[FamilyInstallAborted]
    .left
    .map(aborted => InstallError.Family(aborted.family, aborted.cause))

  // Forks emit through `ask`, not `tell`: each worker blocks until its line is written, so per-family order
  // holds, nothing is buffered at scope teardown, and a sink failure lands on the emitting worker.
  private def runFanOut(
      plan: InstallPlan,
      root: os.Path,
      digests: Map[FamilyName, Sha256Digest],
      sink: InstallEventSink,
  ): Unit = supervised:
    val actor                        = Actor.create(sink)
    val serialised: InstallEventSink = event => actor.ask(_.emit(event))
    Flow
      .fromIterable(plan.families)
      .mapParUnordered(parallelism(plan))(family =>
        outcome(family, root, digests.get(family.name), serialised),
      )
      .runForeach(abortOnFailure)

  private def parallelism(plan: InstallPlan): Int =
    math.max(1, math.min(FontInstaller.maxConcurrentInstalls, plan.families.size))

  private def outcome(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      events: InstallEventSink,
  ): Either[FamilyInstallAborted, Unit] = installFamily(planned, root, expected, events).left.map(cause =>
    FamilyInstallAborted(planned.name, cause),
  )

  // Raised on the flow's own thread, as each result is received, rather than inside the worker: Ox wraps a
  // worker's exception in `ChannelClosedException`, whereas one thrown here ends the fan-out (interrupting
  // the in-flight siblings) and crosses `supervised` unchanged, so the boundary catches exactly one type.
  private def abortOnFailure(outcome: Either[FamilyInstallAborted, Unit]): Unit = outcome match
    case Right(())     => ()
    case Left(aborted) => throw aborted

  // `timeoutEither`, never the throwing `timeout`: on overrun Ox interrupts the body and waits for it, so the
  // cleanup below has run when the `Left` is observed, and the `Left` then travels the ordinary path.
  private def installFamily(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      events: InstallEventSink,
  ): Either[FamilyInstallError, Unit] =
    timeoutEither(familyDeadline, FamilyInstallError.TimedOut(familyDeadline)):
      installFamilyNow(planned, root, expected, events)

  private def installFamilyNow(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      events: InstallEventSink,
  ): Either[FamilyInstallError, Unit] = either:
    events.emit(InstallEvent.Started(planned.name, planned.url))
    withTempZip(zip => fetchAndStage(planned, root, expected, zip)).ok()
    events.emit(InstallEvent.Installed(planned.name, planned.targetDir))

  private def fetchAndStage(
      planned: PlannedFamily,
      root: os.Path,
      expected: Option[Sha256Digest],
      zip: os.Path,
  ): Either[FamilyInstallError, Unit] = either:
    val actual = download(planned.url, zip).ok()
    verify(actual, expected).ok()
    withStaging(root, planned.name)(staging => extractAndSwap(zip, staging, planned.targetDir)).ok()

  private def extractAndSwap(
      zip: os.Path,
      staging: os.Path,
      target: os.Path,
  ): Either[FamilyInstallError, Unit] = either:
    ArchiveExtractor
      .extract(zip, staging, limits)
      .left
      .map(FamilyInstallError.Extraction(zip, staging, _))
      .ok()
      .discard
    DirectorySwap.replace(staging, target).left.map(FamilyInstallError.Swap(_)).ok()

  // Both size checks are the port's; the consumer only copies and hashes, and its own IO failure is a copy error.
  private def download(url: DownloadUrl, zip: os.Path): Either[FamilyInstallError, Sha256Digest] =
    http.get(HttpRequest(url.url), limits.download)(body => copyHashing(body, zip)) match
      case Left(error)          => Left(FamilyInstallError.Download(url, error))
      case Right(Left(cause))   => Left(FamilyInstallError.Copy(url, zip, cause))
      case Right(Right(digest)) => Right(digest)

  // Hashed while copied so the archive is read once and never held in memory. The body itself is owned and
  // closed by the port; only the file is closed here, and its close error counts as a copy failure.
  private def copyHashing(body: InputStream, zip: os.Path): Either[String, Sha256Digest] =
    val digest = MessageDigest.getInstance("SHA-256")
    Using
      .resource(Files.newOutputStream(zip.toNIO))(out =>
        DigestInputStream(body, digest).transferTo(out).discard,
      )
      .catching[IOException]
      .map(_ => Sha256Digest.fromBytes(digest.digest()))
      .left
      .map(Diagnostics.describe)

  private def verify(actual: Sha256Digest, expected: Option[Sha256Digest]): Either[FamilyInstallError, Unit] =
    expected match
      case Some(wanted) if wanted != actual => Left(FamilyInstallError.ChecksumMismatch(actual, wanted))
      case _                                => Right(())

  // The temp zip and the staging directory are removed in `finally`, so an interrupt (§6.8) or a deadline
  // leaves nothing behind while an existing `<root>/<Family>` stays untouched until the swap.
  private def withTempZip(
      use: os.Path => Either[FamilyInstallError, Unit],
  ): Either[FamilyInstallError, Unit] = createTempZip().flatMap: zip =>
    try use(zip)
    finally Cleanup.removeFile(zip)

  private def createTempZip(): Either[FamilyInstallError, os.Path] = os
    .temp(
      dir = tempDir,
      prefix = FontInstaller.tempZipPrefix,
      suffix = FontInstaller.tempZipSuffix,
      deleteOnExit = false,
    )
    .catching[IOException]
    .left
    .map(error => FamilyInstallError.TempZip(Diagnostics.describe(error)))

  private def withStaging(root: os.Path, family: FamilyName)(
      use: os.Path => Either[FamilyInstallError, Unit],
  ): Either[FamilyInstallError, Unit] = createStaging(root, family).flatMap: staging =>
    try use(staging)
    finally Cleanup.removeTree(staging)

  // Staged inside `root` so the final rename is a same-filesystem `rename(2)`; the dot prefix hides it from
  // font tooling that scans the directory while an install is in flight.
  private def createStaging(root: os.Path, family: FamilyName): Either[FamilyInstallError, os.Path] = os.temp
    .dir(dir = root, prefix = s".${family.value}-", deleteOnExit = false)
    .catching[IOException]
    .left
    .map(error => FamilyInstallError.Staging(root, Diagnostics.describe(error)))

  private def refreshCache(request: InstallRequest, sink: InstallEventSink): Either[InstallError, Unit] =
    request.refreshFontCache match
      case RefreshFontCache.Disabled => Right(())
      case RefreshFontCache.Enabled  => refresher.availability match
          case FontCacheAvailability.Unavailable => Right(sink.emit(InstallEvent.FontCacheUnavailable))
          case FontCacheAvailability.Available   => runFontCache(request.root, sink)

  private def runFontCache(root: os.Path, sink: InstallEventSink): Either[InstallError, Unit] = either:
    sink.emit(InstallEvent.RefreshingFontCache(root))
    refresher.refresh(root).left.map(InstallError.FontCache(root, _)).ok()
    sink.emit(InstallEvent.FontCacheRefreshed)

object FontInstaller:
  /** Downloads all target one host and are bandwidth bound; a small fan-out captures most of the speedup. */
  val maxConcurrentInstalls: Int = 4

  /** Bounds one family's download plus extraction; composes with sibling cancellation instead of a global clock. */
  val defaultFamilyDeadline: FiniteDuration = 10.minutes

  /** Stricter than Go's unbounded manifest fetch, acceptable because a manifest failure is only a warning. */
  val defaultManifestTimeout: FiniteDuration = 30.seconds

  val tempZipPrefix: String = "nerd-font-"
  val tempZipSuffix: String = ".zip"

/**
 * The one sanctioned exception for a recoverable error (§6.5): a family's `Left` is raised inside the flow
 * so that Ox ends the fan-out and interrupts the in-flight siblings, exactly as Go's `errgroup` cancels its
 * context. `FontInstaller.install` catches precisely this type at its boundary and turns it back into
 * `InstallError.Family`; it never escapes the module. No stack trace: it carries a value, not a defect.
 */
final private[install] class FamilyInstallAborted(val family: FamilyName, val cause: FamilyInstallError)
    extends Exception(cause.render(family))
    with NoStackTrace

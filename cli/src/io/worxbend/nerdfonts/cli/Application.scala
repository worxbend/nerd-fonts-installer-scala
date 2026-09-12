package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallRequest
import io.worxbend.nerdfonts.releases.Release
import io.worxbend.nerdfonts.releases.ReleaseError
import io.worxbend.nerdfonts.releases.ReleaseSelection

import java.io.PrintWriter
import java.nio.file.Path

import zio.IO
import zio.Ref
import zio.UIO
import zio.ZIO

/**
 * The Go `run` after flag parsing: resolve a config (explicit → env → discovered), or print the font
 * names, then install. Sees only `CliOptions` and `AppDependencies`, never the argument array, so every path
 * is a test on the returned `Either` value.
 *
 * `--font-names` and the install path share the same explicit/env/discovered lookup but differ in what they
 * do when nothing is found (fall back to `latest` versus fail) and in whether `Using config` is announced,
 * which is why the lookup is split into steps rather than shared as one function.
 *
 * Interruption (SIGINT, §6.8) is turned into a typed `AppFailure.Interrupted` here, with the phase — tracked
 * in a `Ref` that flips to `Install` the instant the engine takes over — distinguishing `install fonts:
 * interrupted` from a bare `interrupted`. The conversion is deliberately co-located with reporting: an
 * external `Fiber.interrupt` (the real SIGINT path) is *sticky*, so once the work is interrupted every later
 * interruptible step would re-interrupt and swallow both the typed failure and its stderr line. `run` therefore
 * absorbs the interrupt into a plain value while still uninterruptible (via a caller-supplied `restore`) and
 * hands that value back so the boundary can report it in the same uninterruptible region. A self-`ZIO.interrupt`
 * (the test fakes) never sets that sticky flag, so the simpler [[run]] overload that opens its own mask is
 * enough for the unit tests.
 */
object Application:
  def run(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
  ): UIO[Either[AppFailure, AppOutcome]] =
    ZIO.uninterruptibleMask(restore => run(options, deps, out, err, restore))

  /**
   * As [[run]], but the mask is opened by the caller so the same uninterruptible region also covers reporting
   * (the process boundary passes its own `restore`). `restore` re-enables interruption only for the work, so a
   * SIGINT still tears down the download's finalizers; the fold that follows runs uninterruptibly, which is
   * what makes the absorbed interrupt survive as a value rather than re-firing.
   */
  def run(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
      restore: ZIO.InterruptibilityRestorer,
  ): UIO[Either[AppFailure, AppOutcome]] = Ref
    .make(InterruptPhase.BeforeInstall)
    .flatMap: phase =>
      restore(program(options, deps, out, err, phase)).foldCauseZIO(
        cause =>
          // A typed failure is preferred over the interrupt, and the order matters. `Cause#isInterrupted`
          // is true if *any* node in the tree is an interrupt, and `ZIO.foreachPar` interrupts the
          // surviving siblings as soon as one family fails, producing `Both(Fail(real), Interrupt(...))`.
          // Checking the interrupt first therefore replaced the real diagnostic with "interrupted" for
          // every multi-family install -- while a single-family install, which `foreachPar` runs inline
          // without forking, reported correctly. `failureOrCause` reads the typed failure if there is one
          // and only yields the raw cause when there is not, so a genuine interrupt still lands below.
          cause.failureOrCause match
            case Left(failure) => ZIO.succeed(Left(failure))
            case Right(rest)   =>
              if rest.isInterrupted then phase.get.map(p => Left(AppFailure.Interrupted(p)))
              else ZIO.failCause(rest)
        ,
        outcome => ZIO.succeed(Right(outcome)),
      )

  private def program(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
      phase: Ref[InterruptPhase],
  ): IO[AppFailure, AppOutcome] = options.mode match
    case CliMode.FontNames => printFontNames(options, deps, out)
    case CliMode.Install   =>
      resolveConfig(options, deps, err).flatMap(install(_, options.dryRun, deps, out, err, phase))

  private[cli] def printFontNames(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
  ): IO[AppFailure, AppOutcome] =
    for
      selector <- configuredSelector(options, deps)
      releases <- deps.listReleases().mapError(AppFailure.Release(_))
      release  <- ZIO.fromEither(selectRelease(releases, selector))
      _        <- ZIO.succeed(writeFontNames(release, out))
    yield AppOutcome.FontNamesPrinted

  // `--font-names` borrows a discovered config's release but never announces the file, as the reference does.
  private def configuredSelector(
      options: CliOptions,
      deps: AppDependencies,
  ): IO[AppFailure, ReleaseSelector] = explicitPath(options, deps.environment).flatMap:
    case Some(raw) => loadExplicit(raw, deps).map(_.selector)
    case None      => discover(deps).map(_.fold(ReleaseSelector.Latest)(_.config.selector))

  /** Go's `selectRelease`: an empty listing is `ErrNoReleases` whatever the selector asked for. */
  private[cli] def selectRelease(
      releases: Vector[Release],
      selector: ReleaseSelector,
  ): Either[AppFailure, Release] =
    if releases.isEmpty then Left(AppFailure.Release(ReleaseError.NoReleases))
    else ReleaseSelection.select(releases, selector).left.map(AppFailure.Release(_))

  private def writeFontNames(release: Release, out: PrintWriter): Unit =
    out.println(s"# ${release.tag.value}")
    out.println("families:")
    release.families.foreach(family => out.println(s"  - $family"))

  private[cli] def resolveConfig(
      options: CliOptions,
      deps: AppDependencies,
      err: PrintWriter,
  ): IO[AppFailure, InstallConfig] = explicitPath(options, deps.environment).flatMap:
    case Some(raw) => loadExplicit(raw, deps)
    case None      => discover(deps).flatMap:
        case Some(found) => ZIO.succeed(err.println(s"Using config ${found.path}")).as(found.config)
        case None        => deps.configCandidates().flatMap(c => ZIO.fail(AppFailure.NoConfig(c)))

  // The flag wins over the variable; a blank variable falls through to discovery (Go `effectiveConfigPath`).
  private def explicitPath(options: CliOptions, env: Environment): UIO[Option[String]] =
    options.explicitConfig match
      case some @ Some(_) => ZIO.succeed(some)
      case None           => env.variable(ConfigLocations.configVariable).map(_.map(_.trim).filter(_.nonEmpty))

  private def loadExplicit(raw: String, deps: AppDependencies): IO[AppFailure, InstallConfig] =
    resolvePath(raw, deps.environment).flatMap(deps.loadConfig).mapError(AppFailure.Config(_, raw))

  // The loader needs an absolute path while the message keeps the raw text (`AppFailure.renderAsTyped`
  // substitutes it back in). An absolute path needs no working directory, so its absence only fails a relative
  // one. Anything argv or an environment variable can carry is a well-formed POSIX path (no NUL), so `Path.of`
  // cannot throw here. `--config ""` resolves to the working directory itself, which Go never opens (it hands
  // the empty string straight to `os.ReadFile` and gets `no such file or directory`); reporting `NotFound`
  // directly avoids reading the cwd as a file and getting a different error shape (`Is a directory`).
  private def resolvePath(raw: String, env: Environment): IO[ConfigError, os.Path] =
    val path = Path.of(raw)
    if path.isAbsolute then ZIO.succeed(os.Path(path.normalize()))
    else
      env.workingDirectory
        .mapError(ConfigError.NoWorkingDirectory(_))
        .flatMap: cwd =>
          if raw.isEmpty then ZIO.fail(ConfigError.NotFound(cwd))
          else ZIO.succeed(os.Path(cwd.toNIO.resolve(path).normalize()))

  private def discover(deps: AppDependencies): IO[AppFailure, Option[DiscoveredConfig]] =
    deps.discoverConfig().mapError(AppFailure.DiscoveredConfig(_))

  private[cli] def install(
      config: InstallConfig,
      dryRun: DryRun,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
      phase: Ref[InterruptPhase],
  ): IO[AppFailure, AppOutcome] =
    for
      root   <- deps.expandDestination(config.destination).mapError(AppFailure.Destination(_))
      request = InstallRequest(config.selector, root, config.families, config.refreshFontCache, dryRun)
      // From here on a SIGINT is Go's `install fonts: … context canceled`; the phase flip is what
      // `Application.run` reads to add the prefix. Destination expansion above is instantaneous and still
      // counts as "before install", exactly as the Go reference wraps only the engine call.
      _      <- phase.set(InterruptPhase.Install)
      _      <- deps
                  .installFonts(request, ConsoleEventRenderer(out, err, deps.colours))
                  .mapError(AppFailure.Install(_))
    yield dryRun match
      case DryRun.Enabled  => AppOutcome.DryRunPrinted
      case DryRun.Disabled => AppOutcome.Installed

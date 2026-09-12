package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallEventSink
import io.worxbend.nerdfonts.install.InstallRequest
import io.worxbend.nerdfonts.releases.Release
import io.worxbend.nerdfonts.releases.ReleaseError
import io.worxbend.nerdfonts.releases.ReleaseSelection

import java.io.PrintWriter
import java.nio.file.Path

import ox.either
import ox.either.ok

/**
 * The Go `run` after flag parsing: resolve a config (explicit → env → discovered), or print the font
 * names, then install. Sees only `CliOptions` and `AppDependencies`, never picocli or the argument array, so
 * every path is a unit test on an `Either` value.
 *
 * `--font-names` and the install path share the same explicit/env/discovered lookup but differ in what they
 * do when nothing is found (fall back to `latest` versus fail) and in whether `Using config` is
 * announced, which is why the lookup is split into steps rather than shared as one function.
 */
object Application:
  def run(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
  ): Either[AppFailure, AppOutcome] = options.mode match
    case CliMode.FontNames => printFontNames(options, deps, out)
    case CliMode.Install   =>
      resolveConfig(options, deps, err).flatMap(install(_, options.dryRun, deps, out, err))

  private[cli] def printFontNames(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
  ): Either[AppFailure, AppOutcome] = either:
    val selector = configuredSelector(options, deps).ok()
    val releases = deps.listReleases().left.map(AppFailure.Release(_)).ok()
    val release  = selectRelease(releases, selector).ok()
    writeFontNames(release, out)
    AppOutcome.FontNamesPrinted

  // `--font-names` borrows a discovered config's release but never announces the file, as the reference does.
  private def configuredSelector(
      options: CliOptions,
      deps: AppDependencies,
  ): Either[AppFailure, ReleaseSelector] = explicitPath(options, deps.environment) match
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
  ): Either[AppFailure, InstallConfig] = explicitPath(options, deps.environment) match
    case Some(raw) => loadExplicit(raw, deps)
    case None      => discover(deps).flatMap:
        case Some(found) =>
          err.println(s"Using config ${found.path}")
          Right(found.config)
        case None        => Left(AppFailure.NoConfig(deps.configCandidates()))

  // The flag wins over the variable; a blank variable falls through to discovery (Go `effectiveConfigPath`).
  private def explicitPath(options: CliOptions, env: Environment): Option[String] = options.explicitConfig
    .orElse(env.variable(ConfigLocations.configVariable).map(_.trim).filter(_.nonEmpty))

  private def loadExplicit(raw: String, deps: AppDependencies): Either[AppFailure, InstallConfig] =
    resolvePath(raw, deps.environment).flatMap(deps.loadConfig).left.map(AppFailure.Config(_, raw))

  // The loader needs an absolute path while the message keeps the raw text (`AppFailure.renderAsTyped`
  // substitutes it back in). An absolute path needs no working directory, so its absence only fails a relative
  // one. Anything argv or an environment variable can carry is a well-formed POSIX path (no NUL), so `Path.of`
  // cannot throw here. `--config ""` resolves to the working directory itself, which Go never opens (it hands
  // the empty string straight to `os.ReadFile` and gets `no such file or directory`); reporting `NotFound`
  // directly avoids reading the cwd as a file and getting a different error shape (`Is a directory`).
  private def resolvePath(raw: String, env: Environment): Either[ConfigError, os.Path] =
    val path = Path.of(raw)
    if path.isAbsolute then Right(os.Path(path.normalize()))
    else
      env.workingDirectory.left
        .map(ConfigError.NoWorkingDirectory(_))
        .flatMap(cwd =>
          if raw.isEmpty then Left(ConfigError.NotFound(cwd))
          else Right(os.Path(cwd.toNIO.resolve(path).normalize())),
        )

  private def discover(deps: AppDependencies): Either[AppFailure, Option[DiscoveredConfig]] =
    deps.discoverConfig().left.map(AppFailure.DiscoveredConfig(_))

  private[cli] def install(
      config: InstallConfig,
      dryRun: DryRun,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
  ): Either[AppFailure, AppOutcome] = either:
    val root    = deps.expandDestination(config.destination).left.map(AppFailure.Destination(_)).ok()
    val request = InstallRequest(config.selector, root, config.families, config.refreshFontCache, dryRun)
    runInstall(request, deps, ConsoleEventRenderer(out, err, deps.colours)).ok()
    dryRun match
      case DryRun.Enabled  => AppOutcome.DryRunPrinted
      case DryRun.Disabled => AppOutcome.Installed

  // A SIGINT that lands during the install is reported with Go's `install fonts: ` prefix; only this frame
  // knows the phase, so the conversion happens here rather than at the process boundary. The engine's
  // `finally` blocks have already run when the exception reaches this point.
  private def runInstall(
      request: InstallRequest,
      deps: AppDependencies,
      sink: InstallEventSink,
  ): Either[AppFailure, Unit] = Interruptible.run(deps.installFonts(request, sink)) match
    case Left(_)       => Left(AppFailure.Interrupted(InterruptPhase.Install))
    case Right(result) => result.left.map(AppFailure.Install(_))

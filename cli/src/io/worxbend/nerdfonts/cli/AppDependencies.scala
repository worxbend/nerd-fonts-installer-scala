package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.config.ConfigDiscovery
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLoader
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.environment.PathExpander
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.http.JdkHttpClient
import io.worxbend.nerdfonts.http.Url
import io.worxbend.nerdfonts.install.FcCacheRefresher
import io.worxbend.nerdfonts.install.FontInstaller
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.install.InstallEventSink
import io.worxbend.nerdfonts.install.InstallRequest
import io.worxbend.nerdfonts.picker.IconMode
import io.worxbend.nerdfonts.picker.PickerError
import io.worxbend.nerdfonts.picker.PickerOutcome
import io.worxbend.nerdfonts.picker.PickerSession
import io.worxbend.nerdfonts.picker.SttyTerminal
import io.worxbend.nerdfonts.process.JdkProcessRunner
import io.worxbend.nerdfonts.releases.GitHubReleaseCatalogue
import io.worxbend.nerdfonts.releases.Release
import io.worxbend.nerdfonts.releases.ReleaseError
import io.worxbend.nerdfonts.releases.ReleaseUrls

/**
 * The seams between `Application` and the world, as plain functions (the Go `dependencies` struct).
 *
 * Function-typed rather than port traits because each is used at exactly one call site and tests want to
 * replace one at a time with a lambda; in particular `runPicker` hides the `Terminal`, so no raw-mode
 * adapter ever crosses into a CLI test. `environment` and `colours` ride along because the explicit-config
 * variable, path resolution and every renderer need them and nothing below the composition root may read
 * `sys.env`.
 */
final case class AppDependencies(
    environment: Environment,
    colours: ColourMode,
    loadConfig: os.Path => Either[ConfigError, InstallConfig],
    discoverConfig: () => Either[ConfigError, Option[DiscoveredConfig]],
    configCandidates: () => Vector[os.Path],
    listReleases: () => Either[ReleaseError, Vector[Release]],
    runPicker: (Vector[Release], IconMode, ColourMode) => Either[PickerError, PickerOutcome],
    installFonts: (InstallRequest, InstallEventSink) => Either[InstallError, Unit],
    isTerminal: () => Boolean,
    expandDestination: DestinationPath => Either[PathError, os.Path],
)

object AppDependencies:
  /**
   * Test hook, deliberately undocumented for users: when set, every release asset URL (family zips and the
   * checksum manifest) is built below this base instead of the GitHub release page, so the CI interrupt
   * smoke test can point the shipped binary at a local stub. Read here and nowhere else.
   */
  val baseUrlVariable: String = "NERD_FONTS_INSTALLER_BASE_URL"

  /**
   * The composition root: the one place the JDK adapters are built and handed to the engine, the catalogue and
   * the picker. `tempDir` is where `nerd-font-*.zip` downloads are staged. The `SttyTerminal` is created inside
   * `runPicker` so its stdin wrapper only exists when a picker session actually starts.
   */
  def production(env: Environment, tempDir: os.Path): AppDependencies =
    val http      = JdkHttpClient()
    val processes = JdkProcessRunner(env)
    val catalogue = GitHubReleaseCatalogue(http)
    val installer = FontInstaller(http, tempDir, FcCacheRefresher(processes), urls = releaseUrls(env))
    AppDependencies(
      environment = env,
      colours = OutputStyle.detect(env, TerminalProbe.isTerminal()),
      loadConfig = ConfigLoader.load,
      discoverConfig = () => ConfigDiscovery.discover(env, ConfigLoader.load),
      configCandidates = () => ConfigLocations.candidates(env).getOrElse(Vector.empty),
      listReleases = () => catalogue.releases(),
      runPicker =
        (releases, icons, colours) => PickerSession.run(releases, icons, colours, SttyTerminal(processes)),
      installFonts = installer.install,
      isTerminal = () => TerminalProbe.isTerminal(),
      expandDestination = PathExpander.expand(_, env),
    )

  private def releaseUrls(env: Environment): ReleaseUrls = env
    .variable(baseUrlVariable)
    .map(_.trim)
    .filter(_.nonEmpty)
    .fold(ReleaseUrls.github)(base => ReleaseUrls(Url(base)))

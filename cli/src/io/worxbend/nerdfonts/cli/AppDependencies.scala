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
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.Url
import io.worxbend.nerdfonts.http.ZioHttpClient
import io.worxbend.nerdfonts.install.FcCacheRefresher
import io.worxbend.nerdfonts.install.FontInstaller
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.install.InstallEventSink
import io.worxbend.nerdfonts.install.InstallRequest
import io.worxbend.nerdfonts.process.JdkProcessRunner
import io.worxbend.nerdfonts.releases.GitHubReleaseCatalogue
import io.worxbend.nerdfonts.releases.Release
import io.worxbend.nerdfonts.releases.ReleaseError
import io.worxbend.nerdfonts.releases.ReleaseUrls

import zio.IO
import zio.Scope
import zio.UIO
import zio.ZIO

/**
 * The seams between `Application` and the world, as plain functions.
 *
 * Function-typed rather than port traits because each is used at exactly one call site and tests want to
 * replace one at a time with a lambda. The effectful seams are `ZIO` effects with the typed errors of the
 * underlying ports; `environment` and `colours` ride along because the explicit-config variable, path
 * resolution and every renderer need them and nothing below the composition root may read `sys.env`.
 */
final case class AppDependencies(
    environment: Environment,
    colours: ColourMode,
    loadConfig: os.Path => IO[ConfigError, InstallConfig],
    discoverConfig: () => IO[ConfigError, Option[DiscoveredConfig]],
    configCandidates: () => UIO[Vector[os.Path]],
    listReleases: () => IO[ReleaseError, Vector[Release]],
    installFonts: (InstallRequest, InstallEventSink) => IO[InstallError, Unit],
    expandDestination: DestinationPath => IO[PathError, os.Path],
)

object AppDependencies:
  /**
   * Test hook, deliberately undocumented for users: when set, every release asset URL (family zips and the
   * checksum manifest) is built below this base instead of the GitHub release page, so the CI interrupt
   * smoke test can point the shipped binary at a local stub. Read here and nowhere else.
   */
  val baseUrlVariable: String = "NERD_FONTS_INSTALLER_BASE_URL"

  /**
   * The composition root: the one place the adapters are built and handed to the engine, the catalogue and the
   * config loader. The `Scope` bounds the TLS-hardened zio-http client's lifetime (it is released when the run
   * ends). `tempDir` is where `nerd-font-*.zip` downloads are staged.
   */
  def production(env: Environment, tempDir: os.Path): ZIO[Scope, Throwable, AppDependencies] =
    for
      http     <- ZioHttpClient.live.build.map(_.get[HttpClient])
      attached <- ZIO.succeed(TerminalProbe.isTerminal())
      colours  <- OutputStyle.detect(env, attached)
      urls     <- releaseUrls(env)
    yield
      val processes = JdkProcessRunner(env)
      val catalogue = GitHubReleaseCatalogue(http)
      val installer = FontInstaller(http, tempDir, FcCacheRefresher(processes), urls = urls)
      AppDependencies(
        environment = env,
        colours = colours,
        loadConfig = ConfigLoader.load,
        discoverConfig = () => ConfigDiscovery.discover(env, ConfigLoader.load),
        configCandidates = () => ConfigLocations.candidates(env).orElseSucceed(Vector.empty),
        listReleases = () => catalogue.releases(),
        installFonts = installer.install,
        expandDestination = PathExpander.expand(_, env),
      )

  private def releaseUrls(env: Environment): UIO[ReleaseUrls] = env
    .variable(baseUrlVariable)
    .map(_.map(_.trim).filter(_.nonEmpty))
    .map(_.fold(ReleaseUrls.github)(base => ReleaseUrls(Url(base))))

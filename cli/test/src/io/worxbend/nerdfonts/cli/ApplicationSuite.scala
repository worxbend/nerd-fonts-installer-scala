package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.environment.EnvironmentError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.releases.ReleaseError

import java.util.concurrent.atomic.AtomicReference

import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** `Application.run` as a function from options and dependencies to an `Either`, without picocli. */
object ApplicationSuite extends ZIOSpecDefault:
  private val explicit = options(explicitConfig = Some("fonts.yaml"))

  private def loading(selector: ReleaseSelector = ReleaseSelector.Latest): AppDependencies =
    deps().copy(loadConfig = _ => ZIO.succeed(config(selector, "/tmp/fonts", "Hack")))

  override def spec: Spec[TestEnvironment, Any] = suite("Application")(
    test("font names is FontNamesPrinted"):
      application(options(mode = CliMode.FontNames), deps())
        .map(result => assertTrue(result == Right(AppOutcome.FontNamesPrinted)))
    ,
    test("an explicit config installs"):
      application(explicit, loading()).map(result => assertTrue(result == Right(AppOutcome.Installed)))
    ,
    test("a dry run is DryRunPrinted"):
      application(explicit.copy(dryRun = DryRun.Enabled), loading())
        .map(result => assertTrue(result == Right(AppOutcome.DryRunPrinted)))
    ,
    test("an explicit config failure carries the raw path"):
      val d = deps().copy(loadConfig = path => ZIO.fail(ConfigError.NotFound(path)))
      application(explicit, d).map(result =>
        assertTrue(
          result == Left(AppFailure.Config(ConfigError.NotFound(cwd / "fonts.yaml"), "fonts.yaml")),
        ),
      )
    ,
    test("an absolute explicit path is used as given, without a working directory"):
      val seen = AtomicReference(Option.empty[os.Path])
      val env  = Environment.fixed(
        workingDirectory = Left(EnvironmentError.NoWorkingDirectory("gone")),
        homeDirectory = Some(home),
      )
      val d    = deps(env).copy(loadConfig = path => ZIO.succeed { seen.set(Some(path)); hackConfig })
      application(options(explicitConfig = Some("/etc/fonts/../fonts.yaml")), d).map(result =>
        assertTrue(
          result == Right(AppOutcome.Installed),
          seen.get() == Some(os.Path("/etc/fonts.yaml")),
        ),
      )
    ,
    test("a relative explicit path without a working directory is a config failure"):
      val cause = EnvironmentError.NoWorkingDirectory("gone")
      val env   = Environment.fixed(workingDirectory = Left(cause), homeDirectory = Some(home))
      application(explicit, deps(env)).map(result =>
        assertTrue(
          result == Left(AppFailure.Config(ConfigError.NoWorkingDirectory(cause), "fonts.yaml")),
        ),
      )
    ,
    test("an explicit --config flag wins over a set environment variable"):
      val seen = AtomicReference(Option.empty[os.Path])
      val env  = environment(Map(ConfigLocations.configVariable -> "/env/fonts.yaml"))
      val d    = deps(env).copy(
        loadConfig = path => ZIO.succeed { seen.set(Some(path)); hackConfig },
        discoverConfig =
          () => ZIO.dieMessage("discovery must not run when either the flag or the override is set"),
      )
      application(options(explicitConfig = Some("flag.yaml")), d).map(result =>
        assertTrue(result == Right(AppOutcome.Installed), seen.get() == Some(cwd / "flag.yaml")),
      )
    ,
    test("a config failure with both --config and the env var set names the flag path"):
      val env = environment(Map(ConfigLocations.configVariable -> "/env/fonts.yaml"))
      val d   = deps(env).copy(loadConfig = path => ZIO.fail(ConfigError.NotFound(path)))
      application(options(explicitConfig = Some("flag.yaml")), d).map(result =>
        assertTrue(
          result == Left(AppFailure.Config(ConfigError.NotFound(cwd / "flag.yaml"), "flag.yaml")),
        ),
      )
    ,
    test("a blank environment override falls through to discovery"):
      val env = environment(Map(ConfigLocations.configVariable -> "   "))
      val d   =
        deps(env).copy(discoverConfig = () => ZIO.succeed(Some(DiscoveredConfig(cwd / "x.yaml", hackConfig))))
      application(options(), d).map(result => assertTrue(result == Right(AppOutcome.Installed)))
    ,
    test("a discovery failure is reported as such"):
      val cause = ConfigError.NoWorkingDirectory(EnvironmentError.NoWorkingDirectory("gone"))
      application(options(), deps().copy(discoverConfig = () => ZIO.fail(cause)))
        .map(result => assertTrue(result == Left(AppFailure.DiscoveredConfig(cause))))
    ,
    test("no config is NoConfig with the candidates"):
      val env = environment()
      candidates(env).flatMap(cands =>
        application(options(), deps(env)).map(result =>
          assertTrue(result == Left(AppFailure.NoConfig(cands))),
        ),
      )
    ,
    test("an unknown release for font names is a Release failure"):
      application(
        options(explicitConfig = Some("fonts.yaml"), mode = CliMode.FontNames),
        loading(tagged("v1.0.0")),
      ).map(result => assertTrue(result == Left(AppFailure.Release(ReleaseError.NotFound(tag("v1.0.0"))))))
    ,
    test("selecting from an empty listing is NoReleases whatever the selector"):
      assertTrue(
        Application.selectRelease(Vector.empty, tagged("v3.4.0")) ==
          Left(AppFailure.Release(ReleaseError.NoReleases)),
      )
    ,
    test("selecting latest takes the first release"):
      assertTrue(Application.selectRelease(releases, ReleaseSelector.Latest) == Right(latest))
    ,
    test("selecting a tag finds that release"):
      assertTrue(Application.selectRelease(releases, tagged("v3.3.0")) == Right(previous))
    ,
    test("selecting an unknown tag is NotFound with that tag"):
      assertTrue(
        Application.selectRelease(releases, tagged("v9.9.9")) ==
          Left(AppFailure.Release(ReleaseError.NotFound(tag("v9.9.9")))),
      )
    ,
    test("a destination that cannot be expanded is a Destination failure"):
      val d = loading().copy(expandDestination = _ => ZIO.fail(PathError.NoHome))
      application(explicit, d).map(result =>
        assertTrue(result == Left(AppFailure.Destination(PathError.NoHome))),
      )
    ,
    test("an install failure is wrapped"):
      val cause = InstallError.Destination(os.Path("/tmp/fonts"), "permission denied")
      application(explicit, loading().copy(installFonts = (_, _) => ZIO.fail(cause)))
        .map(result => assertTrue(result == Left(AppFailure.Install(cause))))
    ,
    test("an interrupt during the install is Interrupted in the install phase"):
      val d = loading().copy(installFonts = (_, _) => ZIO.interrupt)
      application(explicit, d).map(result =>
        assertTrue(result == Left(AppFailure.Interrupted(InterruptPhase.Install))),
      ),
  )

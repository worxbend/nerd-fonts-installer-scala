package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.releases.ReleaseError

import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The exit-code mapping, case by case. */
object ExitCodeSuite extends ZIOSpecDefault:
  private val otherFailures = Vector(
    AppFailure.Config(ConfigError.NotFound(cwd / "x.yaml"), "x.yaml"),
    AppFailure.DiscoveredConfig(ConfigError.NotFound(cwd / "x.yaml")),
    AppFailure.Release(ReleaseError.Http(HttpError.Status(403))),
    AppFailure.Release(ReleaseError.Decode("bad json")),
    AppFailure.Destination(PathError.NoHome),
    AppFailure.Install(InstallError.Destination(cwd, "permission denied")),
    AppFailure.Interrupted(InterruptPhase.Install),
    AppFailure.Interrupted(InterruptPhase.BeforeInstall),
  )

  override def spec: Spec[TestEnvironment, Any] = suite("ExitCode")(
    test("every outcome is 0"):
      assertTrue(AppOutcome.values.forall(outcome => ExitCode.of(Right(outcome)) == 0))
    ,
    test("no config is 2"):
      assertTrue(ExitCode.of(Left(AppFailure.NoConfig(Vector.empty))) == 2)
    ,
    test("an unknown release is 2"):
      assertTrue(ExitCode.of(Left(AppFailure.Release(ReleaseError.NotFound(tag("v9.9.9"))))) == 2)
    ,
    test("no releases is 2"):
      assertTrue(ExitCode.of(Left(AppFailure.Release(ReleaseError.NoReleases))) == 2)
    ,
    test("every other failure is 1"):
      assertTrue(otherFailures.forall(failure => ExitCode.of(Left(failure)) == 1)),
  )

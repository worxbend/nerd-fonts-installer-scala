package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.picker.PickerError
import io.worxbend.nerdfonts.releases.ReleaseError

/** Go's `exitCodeFor`, case by case. */
final class ExitCodeSuite extends munit.FunSuite:
  test("every outcome is 0"):
    AppOutcome.values.foreach(outcome => assertEquals(ExitCode.of(Right(outcome)), 0, outcome.toString))

  test("no config is 2"):
    assertEquals(ExitCode.of(Left(AppFailure.NoConfig(Vector.empty))), 2)

  test("--interactive without a terminal is 2"):
    assertEquals(ExitCode.of(Left(AppFailure.NotATerminal)), 2)

  test("an unknown release is 2"):
    assertEquals(ExitCode.of(Left(AppFailure.Release(ReleaseError.NotFound(tag("v9.9.9"))))), 2)

  test("no releases is 2"):
    assertEquals(ExitCode.of(Left(AppFailure.Release(ReleaseError.NoReleases))), 2)

  test("every other failure is 1"):
    val failures = Vector(
      AppFailure.Config(ConfigError.NotFound(cwd / "x.yaml"), "x.yaml"),
      AppFailure.DiscoveredConfig(ConfigError.NotFound(cwd / "x.yaml")),
      AppFailure.Release(ReleaseError.Http(HttpError.Status(403))),
      AppFailure.Release(ReleaseError.Decode("bad json")),
      AppFailure.Picker(PickerError.NoReleases),
      AppFailure.UnsafeSelection(ConfigValidationError.NoFamilies),
      AppFailure.Destination(PathError.NoHome),
      AppFailure.Install(InstallError.Destination(cwd, "permission denied")),
      AppFailure.Interrupted(InterruptPhase.Install),
      AppFailure.Interrupted(InterruptPhase.BeforeInstall),
    )
    failures.foreach(failure => assertEquals(ExitCode.of(Left(failure)), 1, failure.toString))

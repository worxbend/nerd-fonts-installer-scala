package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.environment.EnvironmentError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.FamilyNameError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.releases.ReleaseError

/** The message shapes of §4, §7 and §8 that only the CLI adds. */
final class AppFailureSuite extends munit.FunSuite:
  private val path = cwd / "nerd-fonts-installer.yaml"

  test("an explicit config failure echoes the path as typed"):
    assertEquals(
      AppFailure.Config(ConfigError.NotFound(path), "./nerd-fonts-installer.yaml").render,
      "load config ./nerd-fonts-installer.yaml: open /workspace/nerd-fonts-installer.yaml: no such file or directory",
    )

  test("a discovered config failure names the candidate"):
    assertEquals(
      AppFailure.DiscoveredConfig(ConfigError.UnknownField(path, "colour")).render,
      "load discovered config /workspace/nerd-fonts-installer.yaml: parse /workspace/nerd-fonts-installer.yaml: unknown field \"colour\"",
    )

  test("a missing working directory during discovery renders without a prefix"):
    val cause = ConfigError.NoWorkingDirectory(EnvironmentError.NoWorkingDirectory("gone"))
    assertEquals(AppFailure.DiscoveredConfig(cause).render, "locate current directory: gone")

  test("no config lists the candidates"):
    assertEquals(
      AppFailure.NoConfig(Vector(path, cwd / "nerd-fonts-installer.yml")).render,
      "no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: /workspace/nerd-fonts-installer.yaml, /workspace/nerd-fonts-installer.yml",
    )

  test("no config without candidates degrades to the short hint"):
    assertEquals(
      AppFailure.NoConfig(Vector.empty).render,
      "no config found; pass --config or set NERD_FONTS_INSTALLER_CONFIG",
    )

  test("--interactive without a terminal"):
    assertEquals(
      AppFailure.NotATerminal.render,
      "no config found; --interactive requires stdin and stdout terminals",
    )

  test("release errors render bare"):
    assertEquals(AppFailure.Release(ReleaseError.NoReleases).render, "no Nerd Fonts releases found")

  test("an unsafe picker selection carries the install prefix"):
    assertEquals(
      AppFailure.UnsafeSelection(ConfigValidationError.InvalidFamily(FamilyNameError.Unsafe("../x"))).render,
      "install fonts: unsafe font family name \"../x\"",
    )

  test("a destination failure carries the install prefix"):
    assertEquals(AppFailure.Destination(PathError.NoHome).render, "install fonts: $HOME is not defined")

  test("an install failure carries the install prefix"):
    assertEquals(
      AppFailure.Install(InstallError.Destination(os.Path("/fonts"), "permission denied")).render,
      "install fonts: create destination /fonts: permission denied",
    )

  test("an interrupt renders by phase"):
    assertEquals(AppFailure.Interrupted(InterruptPhase.Install).render, "install fonts: interrupted")
    assertEquals(AppFailure.Interrupted(InterruptPhase.BeforeInstall).render, "interrupted")

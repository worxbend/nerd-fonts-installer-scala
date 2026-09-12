package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.environment.EnvironmentError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.releases.ReleaseError

import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The message shapes of §4–§7 that only the CLI adds. */
object AppFailureSuite extends ZIOSpecDefault:
  private val path = cwd / "nerd-fonts-installer.yaml"

  override def spec: Spec[TestEnvironment, Any] = suite("AppFailure")(
    test("an explicit config failure echoes the path as typed"):
      assertTrue(
        AppFailure.Config(ConfigError.NotFound(path), "./nerd-fonts-installer.yaml").render ==
          "load config ./nerd-fonts-installer.yaml: open ./nerd-fonts-installer.yaml: no such file or directory",
      )
    ,
    test("a blank explicit config echoes as typed, not the working directory it resolved to"):
      assertTrue(
        AppFailure.Config(ConfigError.NotFound(cwd), "").render ==
          "load config : open : no such file or directory",
      )
    ,
    test("a discovered config failure names the candidate"):
      assertTrue(
        AppFailure.DiscoveredConfig(ConfigError.Parse(path, "unknown field \"colour\"")).render ==
          "load discovered config /workspace/nerd-fonts-installer.yaml: parse /workspace/nerd-fonts-installer.yaml: unknown field \"colour\"",
      )
    ,
    test("an unsupported config extension carries the discovered prefix"):
      assertTrue(
        AppFailure.DiscoveredConfig(ConfigError.UnsupportedFormat(path, "toml")).render ==
          "load discovered config /workspace/nerd-fonts-installer.yaml: /workspace/nerd-fonts-installer.yaml: unsupported config extension \"toml\"",
      )
    ,
    test("a missing working directory during discovery renders without a prefix"):
      val cause = ConfigError.NoWorkingDirectory(EnvironmentError.NoWorkingDirectory("gone"))
      assertTrue(AppFailure.DiscoveredConfig(cause).render == "locate current directory: gone")
    ,
    test("no config lists the candidates"):
      assertTrue(
        AppFailure.NoConfig(Vector(path, cwd / "nerd-fonts-installer.yml")).render ==
          "no config found; pass --config, set NERD_FONTS_INSTALLER_CONFIG, or create one of: /workspace/nerd-fonts-installer.yaml, /workspace/nerd-fonts-installer.yml",
      )
    ,
    test("no config without candidates degrades to the short hint"):
      assertTrue(
        AppFailure.NoConfig(Vector.empty).render ==
          "no config found; pass --config or set NERD_FONTS_INSTALLER_CONFIG",
      )
    ,
    test("release errors render bare"):
      assertTrue(AppFailure.Release(ReleaseError.NoReleases).render == "no Nerd Fonts releases found")
    ,
    test("a destination failure carries the install prefix"):
      assertTrue(AppFailure.Destination(PathError.NoHome).render == "install fonts: $HOME is not defined")
    ,
    test("an install failure carries the install prefix"):
      assertTrue(
        AppFailure.Install(InstallError.Destination(os.Path("/fonts"), "permission denied")).render ==
          "install fonts: create destination /fonts: permission denied",
      )
    ,
    test("an interrupt during the install renders with the install prefix"):
      assertTrue(AppFailure.Interrupted(InterruptPhase.Install).render == "install fonts: interrupted")
    ,
    test("an interrupt before the install renders bare"):
      assertTrue(AppFailure.Interrupted(InterruptPhase.BeforeInstall).render == "interrupted"),
  )

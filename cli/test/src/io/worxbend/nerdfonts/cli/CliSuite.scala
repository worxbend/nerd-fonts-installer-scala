package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.EnvironmentError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.install.FamilyInstallError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.install.InstallEvent
import io.worxbend.nerdfonts.install.InstallRequest
import io.worxbend.nerdfonts.releases.ReleaseError
import io.worxbend.nerdfonts.releases.ReleaseUrls

import java.util.concurrent.atomic.AtomicReference

import ox.discard

/** Every exit-code path of SPEC §7 `cli`, driven through `Cli.run` with fake dependencies. */
final class CliSuite extends munit.FunSuite:
  private val envVariable = ConfigLocations.configVariable

  private def withConfig(selector: ReleaseSelector = ReleaseSelector.Latest): AppDependencies =
    deps().copy(loadConfig = _ => Right(config(selector, "/tmp/fonts", "Hack")))

  private def recordingInstall(
      base: AppDependencies,
  ): (AppDependencies, AtomicReference[Option[InstallRequest]]) =
    val seen = AtomicReference(Option.empty[InstallRequest])
    val d    = base.copy(installFonts = (request, _) => Right(seen.set(Some(request))))
    (d, seen)

  test("--version prints the Go version line on stdout and exits 0"):
    val result = run(deps(), "--version")
    assertEquals(result.code, 0)
    assertEquals(
      result.out,
      s"nerd-fonts-installer ${BuildInfo.version} (${BuildInfo.commit}, ${BuildInfo.buildDate})\n",
    )
    assertEquals(result.err, "")

  test("--help prints usage with the header on stdout and exits 0"):
    val result = run(deps(), "--help")
    assertEquals(result.code, 0)
    assert(
      result.out.startsWith("Nerd Fonts, installed the boring way.\nUsage: nerd-fonts-installer "),
      result.out,
    )
    assert(result.out.contains("-config, --config=<path>"), result.out)
    assertEquals(result.err, "")

  test("--help lists the options in Go's order with help last"):
    val out     = run(deps(), "--help").out
    val flags   = Vector("--config", "--dry-run", "--font-names", "--version", "--help")
    val offsets = flags.map(flag => out.indexOf(s", $flag"))
    assert(offsets.forall(_ >= 0), out)
    assertEquals(offsets, offsets.sorted, out)

  test("-h and -help are the same as --help"):
    assertEquals(run(deps(), "-h").out, run(deps(), "--help").out)
    assertEquals(run(deps(), "-help").code, 0)

  test("--font-names prints the latest release in YAML shape and never installs"):
    val d      = deps().copy(installFonts = (_, _) => fail("install must not run"))
    val result = run(d, "--font-names")
    assertEquals(result.code, 0)
    assertEquals(result.out, "# v3.4.0\nfamilies:\n  - Hack\n  - JetBrainsMono\n")
    assertEquals(result.err, "")

  test("--font-names uses the release of an explicit config, resolved against the working directory"):
    val seen   = AtomicReference(Option.empty[os.Path])
    val d      = deps().copy(loadConfig = path =>
      seen.set(Some(path))
      Right(config(tagged("v3.3.0"), "/tmp/fonts", "Hack")))
    val result = run(d, "--font-names", "--config", "fonts.yaml")
    assertEquals(result.code, 0)
    assertEquals(result.out, "# v3.3.0\nfamilies:\n  - FiraCode\n  - Meslo\n")
    assertEquals(seen.get(), Some(cwd / "fonts.yaml"))

  test("--font-names honours the environment override and skips discovery"):
    val seen   = AtomicReference(Option.empty[os.Path])
    val d      = deps(environment(Map(envVariable -> " /env/fonts.yaml "))).copy(
      loadConfig = path =>
        seen.set(Some(path))
        Right(config(tagged("v3.3.0"), "/tmp/fonts", "Hack"))
      ,
      discoverConfig = () => fail("discovery must not run when the override is set"),
    )
    val result = run(d, "--font-names")
    assertEquals(result.code, 0)
    assert(result.out.startsWith("# v3.3.0\n"), result.out)
    assertEquals(seen.get(), Some(os.Path("/env/fonts.yaml")))

  test("--font-names borrows a discovered config's release without announcing it"):
    val found  = DiscoveredConfig(cwd / "found.yaml", config(tagged("v3.3.0"), "/tmp/fonts", "Hack"))
    val result = run(deps().copy(discoverConfig = () => Right(Some(found))), "--font-names")
    assertEquals(result.code, 0)
    assert(result.out.startsWith("# v3.3.0\n"), result.out)
    assertEquals(result.err, "")

  test("--font-names with an unknown release exits 2"):
    val result = run(withConfig(tagged("v1.0.0")), "--font-names", "--config", "fonts.yaml")
    assertEquals(result.code, 2)
    assertEquals(result.err, "nerd fonts release \"v1.0.0\" was not found\n")

  test("--font-names with no releases exits 2"):
    val result = run(deps().copy(listReleases = () => Left(ReleaseError.NoReleases)), "--font-names")
    assertEquals(result.code, 2)
    assertEquals(result.err, "no Nerd Fonts releases found\n")

  test("--font-names with a listing failure exits 1"):
    val failing = deps().copy(listReleases = () => Left(ReleaseError.Http(HttpError.Status(403))))
    val result  = run(failing, "--font-names")
    assertEquals(result.code, 1)
    assertEquals(result.err, "list Nerd Fonts releases: 403 Forbidden\n")

  test("--font-names with a broken explicit config exits 1 with the load config prefix"):
    val broken = deps().copy(loadConfig = path => Left(ConfigError.Parse(path, "yaml: boom")))
    val result = run(broken, "--font-names", "--config", "fonts.yaml")
    assertEquals(result.code, 1)
    assertEquals(result.err, "load config fonts.yaml: parse fonts.yaml: yaml: boom\n")

  test("--font-names with a broken discovered config exits 1 with the load discovered config prefix"):
    val broken = ConfigError.Invalid(cwd / "nerd-fonts-installer.yaml", ConfigValidationError.NoFamilies)
    val result = run(deps().copy(discoverConfig = () => Left(broken)), "--font-names")
    assertEquals(result.code, 1)
    assertEquals(
      result.err,
      "load discovered config /workspace/nerd-fonts-installer.yaml: at least one font family is required\n",
    )
    assertEquals(result.out, "")

  test("--font-names with no working directory during discovery exits 1 without a path"):
    val cause  = ConfigError.NoWorkingDirectory(EnvironmentError.NoWorkingDirectory("gone"))
    val result = run(deps().copy(discoverConfig = () => Left(cause)), "--font-names")
    assertEquals(result.code, 1)
    assertEquals(result.err, "locate current directory: gone\n")
    assertEquals(result.out, "")

  test("a flag missing its value is a usage error"):
    val result = run(deps(), "--config")
    assertEquals(result.code, 2)
    assert(result.err.contains("Missing required parameter for option '--config'"), result.err)

  test("an unknown option is a usage error"):
    val result = run(deps(), "--bogus")
    assertEquals(result.code, 2)
    assert(result.err.startsWith("Unknown option: '--bogus'\n"), result.err)
    assertEquals(result.out, "")

  test("single-dash spellings are accepted like Go's flag package"):
    val (d, seen) = recordingInstall(withConfig())
    val result    = run(d, "-config", "fonts.yaml", "-dry-run")
    assertEquals(result.code, 0)
    assertEquals(seen.get().map(_.dryRun), Some(DryRun.Enabled))

  test("--dry-run=false leaves dry run off"):
    val (d, seen) = recordingInstall(withConfig())
    assertEquals(run(d, "--config", "fonts.yaml", "--dry-run=false").code, 0)
    assertEquals(seen.get().map(_.dryRun), Some(DryRun.Disabled))

  test("--config with --dry-run builds the install request from the config"):
    val (d, seen) = recordingInstall(withConfig(tagged("v3.4.0")))
    val result    = run(d, "--config", "fonts.yaml", "--dry-run")
    assertEquals(result.code, 0)
    assertEquals(
      seen.get(),
      Some(
        InstallRequest(
          tagged("v3.4.0"),
          os.Path("/tmp/fonts"),
          Vector(family("Hack")),
          RefreshFontCache.Enabled,
          DryRun.Enabled,
        ),
      ),
    )

  test("a repeated --config keeps the last value, as Go's flag package does"):
    val seen   = AtomicReference(Option.empty[os.Path])
    val d      = deps().copy(loadConfig = path =>
      seen.set(Some(path))
      Right(hackConfig))
    val result = run(d, "--config", "a.yaml", "--config", "b.yaml")
    assertEquals(result.code, 0)
    assertEquals(seen.get(), Some(cwd / "b.yaml"))

  test("a repeated --dry-run stays a dry run and exits 0"):
    val (d, seen) = recordingInstall(withConfig())
    val result    = run(d, "--config", "fonts.yaml", "--dry-run", "--dry-run")
    assertEquals(result.code, 0)
    assertEquals(seen.get().map(_.dryRun), Some(DryRun.Enabled))

  test("positionals are ignored and stop option parsing, so a later --dry-run has no effect"):
    val (d, seen) = recordingInstall(withConfig())
    val result    = run(d, "--config", "fonts.yaml", "extra", "--dry-run")
    assertEquals(result.code, 0)
    assertEquals(seen.get().map(_.dryRun), Some(DryRun.Disabled))

  test("a run with no config found and no explicit config exits 2"):
    val env    = environment()
    val result = run(deps(env))
    assertEquals(result.code, 2)
    assertEquals(
      result.err,
      s"no config found; pass --config, set $envVariable, or create one of: ${candidates(env).mkString(", ")}\n",
    )

  test("the hint degrades when no candidate can be computed"):
    val result = run(deps().copy(configCandidates = () => Vector.empty))
    assertEquals(result.code, 2)
    assertEquals(result.err, s"no config found; pass --config or set $envVariable\n")

  test("an explicit config that fails to load exits 1 with the load config prefix"):
    val d      = deps().copy(loadConfig = path => Left(ConfigError.Unreadable(path, "permission denied")))
    val result = run(d, "--config", "missing.yaml")
    assertEquals(result.code, 1)
    assertEquals(result.err, "load config missing.yaml: read missing.yaml: permission denied\n")

  test("--config \"\" reports not found without reading the working directory"):
    val result = run(deps(), "--config", "")
    assertEquals(result.code, 1)
    assertEquals(result.err, "load config : open : no such file or directory\n")

  test("a discovered config that fails to load exits 1 with the load discovered config prefix"):
    val broken = ConfigError.Invalid(cwd / "nerd-fonts-installer.yaml", ConfigValidationError.NoFamilies)
    val result = run(deps().copy(discoverConfig = () => Left(broken)))
    assertEquals(result.code, 1)
    assertEquals(
      result.err,
      "load discovered config /workspace/nerd-fonts-installer.yaml: at least one font family is required\n",
    )

  test("a discovered config is announced on stderr and installed"):
    val found     = DiscoveredConfig(cwd / "discovered.yaml", hackConfig)
    val (d, seen) = recordingInstall(deps().copy(discoverConfig = () => Right(Some(found))))
    val result    = run(d)
    assertEquals(result.code, 0)
    assertEquals(result.err, "Using config /workspace/discovered.yaml\n")
    assert(seen.get().isDefined)

  test("an explicit config is not announced"):
    val result = run(withConfig(), "--config", "fonts.yaml")
    assertEquals(result.code, 0)
    assertEquals(result.err, "")

  test("the environment override is loaded like --config and installs"):
    val seen      = AtomicReference(Option.empty[os.Path])
    val base      = deps(environment(Map(envVariable -> "/env/fonts.yaml"))).copy(
      loadConfig = path =>
        seen.set(Some(path))
        Right(hackConfig)
      ,
      discoverConfig = () => fail("discovery must not run when the override is set"),
    )
    val (d, done) = recordingInstall(base)
    assertEquals(run(d).code, 0)
    assertEquals(seen.get(), Some(os.Path("/env/fonts.yaml")))
    assert(done.get().isDefined)

  test("--config wins over the environment override when both are set"):
    val seen   = AtomicReference(Option.empty[os.Path])
    val base   = deps(environment(Map(envVariable -> "/env/fonts.yaml"))).copy(
      loadConfig = path =>
        seen.set(Some(path))
        Right(hackConfig)
      ,
      discoverConfig = () => fail("discovery must not run when either the flag or the override is set"),
    )
    val result = run(base, "--config", "flag.yaml")
    assertEquals(result.code, 0)
    assertEquals(seen.get(), Some(cwd / "flag.yaml"))

  test("a --config failure alongside a set environment variable echoes the flag path"):
    val broken = deps(environment(Map(envVariable -> "/env/fonts.yaml")))
      .copy(loadConfig = path => Left(ConfigError.NotFound(path)))
    val result = run(broken, "--config", "flag.yaml")
    assertEquals(result.code, 1)
    assertEquals(result.err, "load config flag.yaml: open flag.yaml: no such file or directory\n")

  test("one failing family prints the single install fonts line and exits 1"):
    val inter   = family("Inter")
    val failure = InstallError.Family(
      inter,
      FamilyInstallError.Download(
        ReleaseUrls.github.download(ReleaseSelector.Latest, inter),
        HttpError.Status(404),
      ),
    )
    val result  = run(withConfig().copy(installFonts = (_, _) => Left(failure)), "--config", "fonts.yaml")
    assertEquals(result.code, 1)
    assertEquals(
      result.err,
      "install fonts: install Nerd Font family Inter: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Inter.zip: 404 Not Found\n",
    )

  test("a destination that cannot be expanded exits 1"):
    val d      = withConfig().copy(expandDestination = _ => Left(PathError.NoHome))
    val result = run(d, "--config", "fonts.yaml")
    assertEquals(result.code, 1)
    assertEquals(result.err, "install fonts: $HOME is not defined\n")

  test("an interrupt during the install exits 1 with install fonts: interrupted"):
    val d      = withConfig().copy(installFonts = (_, _) => throw InterruptedException())
    val result = run(d, "--config", "fonts.yaml")
    assertEquals(result.code, 1)
    assertEquals(result.err, "install fonts: interrupted\n")

  test("an interrupt before the install exits 1 with interrupted"):
    val d      = deps().copy(listReleases = () => throw InterruptedException())
    val result = run(d, "--font-names")
    assertEquals(result.code, 1)
    assertEquals(result.err, "interrupted\n")

  test("dry-run plan events reach stdout and progress events reach stderr"):
    val d      = withConfig().copy(installFonts = (request, sink) =>
      request.families.foreach(name =>
        sink.emit(
          InstallEvent
            .WouldInstall(
              name,
              ReleaseUrls.github.download(request.selector, name),
              request.root / name.value,
            ),
        ),
      )
      sink.emit(InstallEvent.FontCacheUnavailable)
      Right(()))
    val result = run(d, "--config", "fonts.yaml", "--dry-run")
    assertEquals(result.code, 0)
    assertEquals(
      result.out,
      "• Would install Hack from https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip into /tmp/fonts/Hack\n",
    )
    assertEquals(result.err, "• fc-cache is not available; skipping font cache refresh.\n")
    result.discard

  test("the download temp directory is $TMPDIR when it is set"):
    assertEquals(
      Cli.tempDir(environment(Map("TMPDIR" -> "/scratch/downloads"))),
      os.Path("/scratch/downloads"),
    )

  test("the download temp directory falls back to the java.io.tmpdir property when $TMPDIR is blank"):
    val env = environment(Map("TMPDIR" -> ""), properties = Map("java.io.tmpdir" -> "/var/tmp"))
    assertEquals(Cli.tempDir(env), os.Path("/var/tmp"))

  test("the download temp directory falls back to /tmp when neither is set"):
    val env = environment(Map("TMPDIR" -> ""))
    assertEquals(Cli.tempDir(env), os.Path("/tmp"))

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

import zio.Cause
import zio.FiberId
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** Every exit-code path of SPEC §7 `cli`, driven through `Cli.run` with fake dependencies. */
object CliSuite extends ZIOSpecDefault:
  private val envVariable = ConfigLocations.configVariable

  private def withConfig(selector: ReleaseSelector = ReleaseSelector.Latest): AppDependencies =
    deps().copy(loadConfig = _ => ZIO.succeed(config(selector, "/tmp/fonts", "Hack")))

  private def recordingInstall(
      base: AppDependencies,
  ): (AppDependencies, AtomicReference[Option[InstallRequest]]) =
    val seen = AtomicReference(Option.empty[InstallRequest])
    val d    = base.copy(installFonts = (request, _) => ZIO.succeed(seen.set(Some(request))))
    (d, seen)

  override def spec: Spec[TestEnvironment, Any] = suite("Cli")(
    test("--version prints the version line on stdout and exits 0"):
      runCli(deps(), "--version").map(result =>
        assertTrue(
          result.code == 0,
          result.out == s"nerd-fonts-installer ${BuildInfo.version} (${BuildInfo.commit}, ${BuildInfo.buildDate})\n",
          result.err == "",
        ),
      )
    ,
    test("--help prints usage with the header on stdout and exits 0"):
      runCli(deps(), "--help").map(result =>
        assertTrue(
          result.code == 0,
          result.out.startsWith("Nerd Fonts, installed the boring way.\nUsage: nerd-fonts-installer "),
          result.out.contains("--config <path>"),
          result.err == "",
        ),
      )
    ,
    test("--help lists the options in alphabetical order with help last"):
      runCli(deps(), "--help").map { result =>
        val out     = result.out
        val flags   = Vector("--config", "--dry-run", "--font-names", "--version", "--help")
        val offsets = flags.map(flag => out.indexOf(flag))
        assertTrue(offsets.forall(_ >= 0), offsets == offsets.sorted)
      }
    ,
    test("-h and -help are the same as --help"):
      for
        h    <- runCli(deps(), "-h")
        help <- runCli(deps(), "--help")
        long <- runCli(deps(), "-help")
      yield assertTrue(h.out == help.out, long.code == 0)
    ,
    test("--font-names prints the latest release in YAML shape and never installs"):
      val d = deps().copy(installFonts = (_, _) => ZIO.dieMessage("install must not run"))
      runCli(d, "--font-names").map(result =>
        assertTrue(
          result.code == 0,
          result.out == "# v3.4.0\nfamilies:\n  - Hack\n  - JetBrainsMono\n",
          result.err == "",
        ),
      )
    ,
    test("--font-names uses the release of an explicit config, resolved against the working directory"):
      val seen = AtomicReference(Option.empty[os.Path])
      val d    = deps().copy(loadConfig =
        path => ZIO.succeed { seen.set(Some(path)); config(tagged("v3.3.0"), "/tmp/fonts", "Hack") },
      )
      runCli(d, "--font-names", "--config", "fonts.yaml").map(result =>
        assertTrue(
          result.code == 0,
          result.out == "# v3.3.0\nfamilies:\n  - FiraCode\n  - Meslo\n",
          seen.get() == Some(cwd / "fonts.yaml"),
        ),
      )
    ,
    test("--font-names honours the environment override and skips discovery"):
      val seen = AtomicReference(Option.empty[os.Path])
      val d    = deps(environment(Map(envVariable -> " /env/fonts.yaml "))).copy(
        loadConfig =
          path => ZIO.succeed { seen.set(Some(path)); config(tagged("v3.3.0"), "/tmp/fonts", "Hack") },
        discoverConfig = () => ZIO.dieMessage("discovery must not run when the override is set"),
      )
      runCli(d, "--font-names").map(result =>
        assertTrue(
          result.code == 0,
          result.out.startsWith("# v3.3.0\n"),
          seen.get() == Some(os.Path("/env/fonts.yaml")),
        ),
      )
    ,
    test("--font-names borrows a discovered config's release without announcing it"):
      val found = DiscoveredConfig(cwd / "found.yaml", config(tagged("v3.3.0"), "/tmp/fonts", "Hack"))
      runCli(deps().copy(discoverConfig = () => ZIO.succeed(Some(found))), "--font-names").map(result =>
        assertTrue(result.code == 0, result.out.startsWith("# v3.3.0\n"), result.err == ""),
      )
    ,
    test("--font-names with an unknown release exits 2"):
      runCli(withConfig(tagged("v1.0.0")), "--font-names", "--config", "fonts.yaml").map(result =>
        assertTrue(result.code == 2, result.err == "nerd fonts release \"v1.0.0\" was not found\n"),
      )
    ,
    test("--font-names with no releases exits 2"):
      runCli(deps().copy(listReleases = () => ZIO.fail(ReleaseError.NoReleases)), "--font-names").map(
        result => assertTrue(result.code == 2, result.err == "no Nerd Fonts releases found\n"),
      )
    ,
    test("--font-names with a listing failure exits 1"):
      val failing = deps().copy(listReleases = () => ZIO.fail(ReleaseError.Http(HttpError.Status(403))))
      runCli(failing, "--font-names").map(result =>
        assertTrue(result.code == 1, result.err == "list Nerd Fonts releases: 403 Forbidden\n"),
      )
    ,
    test("--font-names with a broken explicit config exits 1 with the load config prefix"):
      val broken = deps().copy(loadConfig = path => ZIO.fail(ConfigError.Parse(path, "yaml: boom")))
      runCli(broken, "--font-names", "--config", "fonts.yaml").map(result =>
        assertTrue(result.code == 1, result.err == "load config fonts.yaml: parse fonts.yaml: yaml: boom\n"),
      )
    ,
    test("--font-names with a broken discovered config exits 1 with the load discovered config prefix"):
      val broken = ConfigError.Invalid(cwd / "nerd-fonts-installer.yaml", ConfigValidationError.NoFamilies)
      runCli(deps().copy(discoverConfig = () => ZIO.fail(broken)), "--font-names").map(result =>
        assertTrue(
          result.code == 1,
          result.err == "load discovered config /workspace/nerd-fonts-installer.yaml: at least one font family is required\n",
          result.out == "",
        ),
      )
    ,
    test("--font-names with no working directory during discovery exits 1 without a path"):
      val cause = ConfigError.NoWorkingDirectory(EnvironmentError.NoWorkingDirectory("gone"))
      runCli(deps().copy(discoverConfig = () => ZIO.fail(cause)), "--font-names").map(result =>
        assertTrue(result.code == 1, result.err == "locate current directory: gone\n", result.out == ""),
      )
    ,
    test("a flag missing its value is a usage error"):
      runCli(deps(), "--config").map(result =>
        assertTrue(
          result.code == 2,
          result.err.startsWith("flag needs an argument: --config\n"),
          result.out == "",
        ),
      )
    ,
    test("an unknown option is a usage error on stderr"):
      runCli(deps(), "--bogus").map(result =>
        assertTrue(
          result.code == 2,
          result.err.startsWith("flag provided but not defined: --bogus\n"),
          result.out == "",
        ),
      )
    ,
    test("a single-dash long flag is a usage error, so a mistyped -dry-run never installs"):
      runCli(withConfig(), "-dry-run", "--config", "fonts.yaml").map(result =>
        assertTrue(
          result.code == 2,
          result.err.startsWith("flag provided but not defined: -dry-run\n"),
          result.out == "",
        ),
      )
    ,
    test("a single-dash -config is a usage error rather than an accepted spelling"):
      runCli(withConfig(), "-config", "fonts.yaml").map(result =>
        assertTrue(result.code == 2, result.err.startsWith("flag provided but not defined: -config\n")),
      )
    ,
    test("--dry-run=false leaves dry run off"):
      val (d, seen) = recordingInstall(withConfig())
      runCli(d, "--config", "fonts.yaml", "--dry-run=false").map(result =>
        assertTrue(result.code == 0, seen.get().map(_.dryRun) == Some(DryRun.Disabled)),
      )
    ,
    test("--config with --dry-run builds the install request from the config"):
      val (d, seen) = recordingInstall(withConfig(tagged("v3.4.0")))
      runCli(d, "--config", "fonts.yaml", "--dry-run").map(result =>
        assertTrue(
          result.code == 0,
          seen.get() == Some(
            InstallRequest(
              tagged("v3.4.0"),
              os.Path("/tmp/fonts"),
              Vector(family("Hack")),
              RefreshFontCache.Enabled,
              DryRun.Enabled,
            ),
          ),
        ),
      )
    ,
    test("a repeated --config keeps the last value"):
      val seen = AtomicReference(Option.empty[os.Path])
      val d    = deps().copy(loadConfig = path => ZIO.succeed { seen.set(Some(path)); hackConfig })
      runCli(d, "--config", "a.yaml", "--config", "b.yaml").map(result =>
        assertTrue(result.code == 0, seen.get() == Some(cwd / "b.yaml")),
      )
    ,
    test("a repeated --dry-run stays a dry run and exits 0"):
      val (d, seen) = recordingInstall(withConfig())
      runCli(d, "--config", "fonts.yaml", "--dry-run", "--dry-run").map(result =>
        assertTrue(result.code == 0, seen.get().map(_.dryRun) == Some(DryRun.Enabled)),
      )
    ,
    test("positionals are ignored and stop option parsing, so a later --dry-run has no effect"):
      val (d, seen) = recordingInstall(withConfig())
      runCli(d, "--config", "fonts.yaml", "extra", "--dry-run").map(result =>
        assertTrue(result.code == 0, seen.get().map(_.dryRun) == Some(DryRun.Disabled)),
      )
    ,
    test("a run with no config found and no explicit config exits 2"):
      val env = environment()
      candidates(env).flatMap(cands =>
        runCli(deps(env)).map(result =>
          assertTrue(
            result.code == 2,
            result.err == s"no config found; pass --config, set $envVariable, or create one of: ${cands.mkString(", ")}\n",
          ),
        ),
      )
    ,
    test("the hint degrades when no candidate can be computed"):
      runCli(deps().copy(configCandidates = () => ZIO.succeed(Vector.empty))).map(result =>
        assertTrue(
          result.code == 2,
          result.err == s"no config found; pass --config or set $envVariable\n",
        ),
      )
    ,
    test("an explicit config that fails to load exits 1 with the load config prefix"):
      val d = deps().copy(loadConfig = path => ZIO.fail(ConfigError.Unreadable(path, "permission denied")))
      runCli(d, "--config", "missing.yaml").map(result =>
        assertTrue(
          result.code == 1,
          result.err == "load config missing.yaml: read missing.yaml: permission denied\n",
        ),
      )
    ,
    test("--config \"\" reports not found without reading the working directory"):
      runCli(deps(), "--config", "").map(result =>
        assertTrue(result.code == 1, result.err == "load config : open : no such file or directory\n"),
      )
    ,
    test("a discovered config that fails to load exits 1 with the load discovered config prefix"):
      val broken = ConfigError.Invalid(cwd / "nerd-fonts-installer.yaml", ConfigValidationError.NoFamilies)
      runCli(deps().copy(discoverConfig = () => ZIO.fail(broken))).map(result =>
        assertTrue(
          result.code == 1,
          result.err == "load discovered config /workspace/nerd-fonts-installer.yaml: at least one font family is required\n",
        ),
      )
    ,
    test("a discovered config is announced on stderr and installed"):
      val found     = DiscoveredConfig(cwd / "discovered.yaml", hackConfig)
      val (d, seen) = recordingInstall(deps().copy(discoverConfig = () => ZIO.succeed(Some(found))))
      runCli(d).map(result =>
        assertTrue(
          result.code == 0,
          result.err == "Using config /workspace/discovered.yaml\n",
          seen.get().isDefined,
        ),
      )
    ,
    test("an explicit config is not announced"):
      runCli(withConfig(), "--config", "fonts.yaml").map(result =>
        assertTrue(result.code == 0, result.err == ""),
      )
    ,
    test("the environment override is loaded like --config and installs"):
      val seen      = AtomicReference(Option.empty[os.Path])
      val base      = deps(environment(Map(envVariable -> "/env/fonts.yaml"))).copy(
        loadConfig = path => ZIO.succeed { seen.set(Some(path)); hackConfig },
        discoverConfig = () => ZIO.dieMessage("discovery must not run when the override is set"),
      )
      val (d, done) = recordingInstall(base)
      runCli(d).map(result =>
        assertTrue(result.code == 0, seen.get() == Some(os.Path("/env/fonts.yaml")), done.get().isDefined),
      )
    ,
    test("--config wins over the environment override when both are set"):
      val seen = AtomicReference(Option.empty[os.Path])
      val base = deps(environment(Map(envVariable -> "/env/fonts.yaml"))).copy(
        loadConfig = path => ZIO.succeed { seen.set(Some(path)); hackConfig },
        discoverConfig =
          () => ZIO.dieMessage("discovery must not run when either the flag or the override is set"),
      )
      runCli(base, "--config", "flag.yaml").map(result =>
        assertTrue(result.code == 0, seen.get() == Some(cwd / "flag.yaml")),
      )
    ,
    test("a --config failure alongside a set environment variable echoes the flag path"):
      val broken = deps(environment(Map(envVariable -> "/env/fonts.yaml")))
        .copy(loadConfig = path => ZIO.fail(ConfigError.NotFound(path)))
      runCli(broken, "--config", "flag.yaml").map(result =>
        assertTrue(
          result.code == 1,
          result.err == "load config flag.yaml: open flag.yaml: no such file or directory\n",
        ),
      )
    ,
    test("one failing family prints the single install fonts line and exits 1"):
      val inter   = family("Inter")
      val failure = InstallError.Family(
        inter,
        FamilyInstallError.Download(
          ReleaseUrls.github.download(ReleaseSelector.Latest, inter),
          HttpError.Status(404),
        ),
      )
      runCli(withConfig().copy(installFonts = (_, _) => ZIO.fail(failure)), "--config", "fonts.yaml").map(
        result =>
          assertTrue(
            result.code == 1,
            result.err == "install fonts: install Nerd Font family Inter: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Inter.zip: 404 Not Found\n",
          ),
      )
    ,
    test("a family that fails while a sibling is still running still reports the real error"):
      // `ZIO.foreachPar` interrupts the surviving siblings the moment one family fails, so the cause is
      // `Both(Fail(real), Interrupt(sibling))`. `Cause#isInterrupted` is true for that shape, so checking it
      // before the typed failure reported every multi-family failure as "install fonts: interrupted" and
      // destroyed the only diagnostic the user gets. A single-family install was unaffected, because
      // `foreachPar` runs one element inline without forking -- which is why the test above passed.
      val inter         = family("Inter")
      val failure       = InstallError.Family(
        inter,
        FamilyInstallError.Download(
          ReleaseUrls.github.download(ReleaseSelector.Latest, inter),
          HttpError.Status(404),
        ),
      )
      val parallelCause = Cause.Both(Cause.fail(failure), Cause.interrupt(FiberId.None))
      runCli(
        withConfig().copy(installFonts = (_, _) => ZIO.failCause(parallelCause)),
        "--config",
        "fonts.yaml",
      ).map(result =>
        assertTrue(
          result.code == 1,
          result.err == "install fonts: install Nerd Font family Inter: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Inter.zip: 404 Not Found\n",
        ),
      )
    ,
    test("a destination that cannot be expanded exits 1"):
      val d = withConfig().copy(expandDestination = _ => ZIO.fail(PathError.NoHome))
      runCli(d, "--config", "fonts.yaml").map(result =>
        assertTrue(result.code == 1, result.err == "install fonts: $HOME is not defined\n"),
      )
    ,
    test("an interrupt during the install exits 1 with install fonts: interrupted"):
      val d = withConfig().copy(installFonts = (_, _) => ZIO.interrupt)
      runCli(d, "--config", "fonts.yaml").map(result =>
        assertTrue(result.code == 1, result.err == "install fonts: interrupted\n"),
      )
    ,
    test("an interrupt before the install exits 1 with interrupted"):
      val d = deps().copy(listReleases = () => ZIO.interrupt)
      runCli(d, "--font-names").map(result => assertTrue(result.code == 1, result.err == "interrupted\n"))
    ,
    test("dry-run plan events reach stdout and progress events reach stderr"):
      val d = withConfig().copy(installFonts =
        (request, sink) =>
          ZIO.succeed {
            request.families.foreach(name =>
              sink.emit(
                InstallEvent.WouldInstall(
                  name,
                  ReleaseUrls.github.download(request.selector, name),
                  request.root / name.value,
                ),
              ),
            )
            sink.emit(InstallEvent.FontCacheUnavailable)
          },
      )
      runCli(d, "--config", "fonts.yaml", "--dry-run").map(result =>
        assertTrue(
          result.code == 0,
          result.out == "• Would install Hack from https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip into /tmp/fonts/Hack\n",
          result.err == "• fc-cache is not available; skipping font cache refresh.\n",
        ),
      )
    ,
    test("the download temp directory is $TMPDIR when it is set"):
      Cli
        .tempDir(environment(Map("TMPDIR" -> "/scratch/downloads")))
        .map(dir => assertTrue(dir == os.Path("/scratch/downloads")))
    ,
    test("the download temp directory falls back to the java.io.tmpdir property when $TMPDIR is blank"):
      val env = environment(Map("TMPDIR" -> ""), properties = Map("java.io.tmpdir" -> "/var/tmp"))
      Cli.tempDir(env).map(dir => assertTrue(dir == os.Path("/var/tmp")))
    ,
    test("the download temp directory falls back to /tmp when neither is set"):
      Cli.tempDir(environment(Map("TMPDIR" -> ""))).map(dir => assertTrue(dir == os.Path("/tmp"))),
  )

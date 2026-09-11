package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyNameError
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.install.FamilyInstallError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.install.InstallEvent
import io.worxbend.nerdfonts.install.InstallRequest
import io.worxbend.nerdfonts.picker.IconMode
import io.worxbend.nerdfonts.picker.PickerOutcome
import io.worxbend.nerdfonts.releases.ReleaseError
import io.worxbend.nerdfonts.releases.ReleaseUrls

import java.util.concurrent.atomic.AtomicReference

import ox.discard

/** Every exit-code path of SPEC §9 `cli`, driven through `Cli.run` with fake dependencies. */
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
    assertEquals(result.err, "load config fonts.yaml: parse /workspace/fonts.yaml: yaml: boom\n")

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

  test("positionals are ignored and stop option parsing, so a later --dry-run has no effect"):
    val (d, seen) = recordingInstall(withConfig())
    val result    = run(d, "--config", "fonts.yaml", "extra", "--dry-run")
    assertEquals(result.code, 0)
    assertEquals(seen.get().map(_.dryRun), Some(DryRun.Disabled))

  test("an invalid --icons value exits 2 with the Go message, even alongside --version"):
    val result = run(deps(), "--version", "--icons", "bogus")
    assertEquals(result.code, 2)
    assertEquals(result.err, "invalid --icons \"bogus\"; use auto, nerd, unicode, or ascii\n")
    assertEquals(result.out, "")

  test("--icons is trimmed and lower-cased before validation and reaches the picker"):
    val seen   = AtomicReference(Option.empty[IconMode])
    val d      = deps().copy(
      isTerminal = () => true,
      runPicker = (_, icons, _) =>
        seen.set(Some(icons))
        Right(PickerOutcome.Cancelled),
    )
    val result = run(d, "--interactive", "--icons", " NERD ")
    assertEquals(result.code, 0)
    assertEquals(seen.get(), Some(IconMode.Nerd))

  test("no config without --interactive exits 2 with the candidate hint"):
    val env    = environment()
    val result = run(deps(env))
    assertEquals(result.code, 2)
    assertEquals(
      result.err,
      s"no config found; pass --config, set $envVariable, or create one of: ${candidates(env).mkString(", ")}\n",
    )

  test("no config in a terminal without --interactive still exits 2 and never starts the picker"):
    val d      = deps().copy(isTerminal = () => true, runPicker = (_, _, _) => fail("picker must not run"))
    val result = run(d)
    assertEquals(result.code, 2)
    assert(result.err.startsWith("no config found; pass --config"), result.err)

  test("the hint degrades when no candidate can be computed"):
    val result = run(deps().copy(configCandidates = () => Vector.empty))
    assertEquals(result.code, 2)
    assertEquals(result.err, s"no config found; pass --config or set $envVariable\n")

  test("--interactive without a terminal exits 2"):
    val result = run(deps(), "--interactive")
    assertEquals(result.code, 2)
    assertEquals(result.err, "no config found; --interactive requires stdin and stdout terminals\n")

  test("an explicit config that fails to load exits 1 with the load config prefix"):
    val d      = deps().copy(loadConfig = path => Left(ConfigError.Unreadable(path, "permission denied")))
    val result = run(d, "--config", "missing.yaml")
    assertEquals(result.code, 1)
    assertEquals(result.err, "load config missing.yaml: read /workspace/missing.yaml: permission denied\n")

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

  test("a cancelled picker exits 0 after the interactive banner"):
    val result = run(deps().copy(isTerminal = () => true), "--interactive")
    assertEquals(result.code, 0)
    assert(
      result.err.startsWith("No config found. Starting interactive mode...\n\n  ✦ nerd-fonts-installer\n"),
      result.err,
    )
    assert(result.err.contains("✓ Releases loaded"), result.err)
    assertEquals(result.out, "")

  test("a rejected picker selection exits 1 with the install prefix"):
    val rejected = PickerOutcome.Rejected(ConfigValidationError.InvalidFamily(FamilyNameError.Unsafe("../x")))
    val d        = deps().copy(isTerminal = () => true, runPicker = (_, _, _) => Right(rejected))
    val result   = run(d, "--interactive")
    assertEquals(result.code, 1)
    assert(result.err.endsWith("install fonts: unsafe font family name \"../x\"\n"), result.err)

  test("a picker selection is installed"):
    val (d, seen) = recordingInstall(
      deps().copy(isTerminal = () => true, runPicker = (_, _, _) => Right(PickerOutcome.Selected(hackConfig))),
    )
    assertEquals(run(d, "--interactive").code, 0)
    assertEquals(seen.get().map(_.families), Some(Vector(family("Hack"))))

  test("no releases on the picker path exits 2 and never opens the picker"):
    val d      = deps().copy(
      isTerminal = () => true,
      listReleases = () => Left(ReleaseError.NoReleases),
      runPicker = (_, _, _) => fail("picker must not run"),
    )
    val result = run(d, "--interactive")
    assertEquals(result.code, 2)
    assert(result.err.endsWith("no Nerd Fonts releases found\n"), result.err)

  test("one failing family prints the single install fonts line and exits 1"):
    val inter   = family("Inter")
    val failure = InstallError.Family(
      inter,
      FamilyInstallError.Download(ReleaseUrls.download(ReleaseSelector.Latest, inter), HttpError.Status(404)),
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
            .WouldInstall(name, ReleaseUrls.download(request.selector, name), request.root / name.value),
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

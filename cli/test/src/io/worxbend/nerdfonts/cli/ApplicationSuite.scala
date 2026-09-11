package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.config.DiscoveredConfig
import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.environment.EnvironmentError
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyNameError
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.picker.PickerError
import io.worxbend.nerdfonts.picker.PickerOutcome
import io.worxbend.nerdfonts.picker.TerminalError
import io.worxbend.nerdfonts.releases.ReleaseError

import java.util.concurrent.atomic.AtomicReference

/** `Application.run` as a function from options and dependencies to an `Either`, without picocli. */
final class ApplicationSuite extends munit.FunSuite:
  private val explicit = options(explicitConfig = Some("fonts.yaml"))

  private def loading(selector: ReleaseSelector = ReleaseSelector.Latest): AppDependencies =
    deps().copy(loadConfig = _ => Right(config(selector, "/tmp/fonts", "Hack")))

  test("font names is FontNamesPrinted"):
    assertEquals(application(options(mode = CliMode.FontNames), deps()), Right(AppOutcome.FontNamesPrinted))

  test("an explicit config installs"):
    assertEquals(application(explicit, loading()), Right(AppOutcome.Installed))

  test("a dry run is DryRunPrinted"):
    assertEquals(
      application(explicit.copy(dryRun = DryRun.Enabled), loading()),
      Right(AppOutcome.DryRunPrinted),
    )

  test("an explicit config failure carries the raw path"):
    val d = deps().copy(loadConfig = path => Left(ConfigError.NotFound(path)))
    assertEquals(
      application(explicit, d),
      Left(AppFailure.Config(ConfigError.NotFound(cwd / "fonts.yaml"), "fonts.yaml")),
    )

  test("an absolute explicit path is used as given, without a working directory"):
    val seen = AtomicReference(Option.empty[os.Path])
    val env  = Environment.fixed(
      workingDirectory = Left(EnvironmentError.NoWorkingDirectory("gone")),
      homeDirectory = Some(home),
    )
    val d    = deps(env).copy(loadConfig = path =>
      seen.set(Some(path))
      Right(hackConfig))
    assertEquals(
      application(options(explicitConfig = Some("/etc/fonts/../fonts.yaml")), d),
      Right(AppOutcome.Installed),
    )
    assertEquals(seen.get(), Some(os.Path("/etc/fonts.yaml")))

  test("a relative explicit path without a working directory is a config failure"):
    val cause = EnvironmentError.NoWorkingDirectory("gone")
    val env   = Environment.fixed(workingDirectory = Left(cause), homeDirectory = Some(home))
    assertEquals(
      application(explicit, deps(env)),
      Left(AppFailure.Config(ConfigError.NoWorkingDirectory(cause), "fonts.yaml")),
    )

  test("a blank environment override falls through to discovery"):
    val env = environment(Map(ConfigLocations.configVariable -> "   "))
    val d   = deps(env).copy(discoverConfig = () => Right(Some(DiscoveredConfig(cwd / "x.yaml", hackConfig))))
    assertEquals(application(options(), d), Right(AppOutcome.Installed))

  test("a discovery failure is reported as such"):
    val cause = ConfigError.NoWorkingDirectory(EnvironmentError.NoWorkingDirectory("gone"))
    assertEquals(
      application(options(), deps().copy(discoverConfig = () => Left(cause))),
      Left(AppFailure.DiscoveredConfig(cause)),
    )

  test("no config and no --interactive is NoConfig with the candidates"):
    val env = environment()
    assertEquals(application(options(), deps(env)), Left(AppFailure.NoConfig(candidates(env))))

  test("--interactive without a terminal is NotATerminal"):
    assertEquals(
      application(options(interactive = Interactive.Requested), deps()),
      Left(AppFailure.NotATerminal),
    )

  test("a cancelled picker is PickerCancelled"):
    val d = deps().copy(isTerminal = () => true)
    assertEquals(
      application(options(interactive = Interactive.Requested), d),
      Right(AppOutcome.PickerCancelled),
    )

  test("a rejected selection is UnsafeSelection"):
    val cause = ConfigValidationError.InvalidFamily(FamilyNameError.Unsafe("../x"))
    val d     =
      deps().copy(isTerminal = () => true, runPicker = (_, _, _) => Right(PickerOutcome.Rejected(cause)))
    assertEquals(
      application(options(interactive = Interactive.Requested), d),
      Left(AppFailure.UnsafeSelection(cause)),
    )

  test("a picker that cannot enter raw mode is a Picker failure"):
    val cause = PickerError.Terminal(TerminalError.RawModeUnavailable("stty -g: exit status 1"))
    val d     = deps().copy(isTerminal = () => true, runPicker = (_, _, _) => Left(cause))
    assertEquals(application(options(interactive = Interactive.Requested), d), Left(AppFailure.Picker(cause)))

  test("a selected picker config installs"):
    val d =
      deps().copy(isTerminal = () => true, runPicker = (_, _, _) => Right(PickerOutcome.Selected(hackConfig)))
    assertEquals(application(options(interactive = Interactive.Requested), d), Right(AppOutcome.Installed))

  test("a listing failure on the picker path is a Release failure"):
    val d = deps().copy(isTerminal = () => true, listReleases = () => Left(ReleaseError.NoReleases))
    assertEquals(
      application(options(interactive = Interactive.Requested), d),
      Left(AppFailure.Release(ReleaseError.NoReleases)),
    )

  test("an unknown release for font names is a Release failure"):
    assertEquals(
      application(
        options(explicitConfig = Some("fonts.yaml"), mode = CliMode.FontNames),
        loading(tagged("v1.0.0")),
      ),
      Left(AppFailure.Release(ReleaseError.NotFound(tag("v1.0.0")))),
    )

  test("selecting from an empty listing is NoReleases whatever the selector"):
    assertEquals(
      Application.selectRelease(Vector.empty, tagged("v3.4.0")),
      Left(AppFailure.Release(ReleaseError.NoReleases)),
    )

  test("selecting latest takes the first release"):
    assertEquals(Application.selectRelease(releases, ReleaseSelector.Latest), Right(latest))

  test("selecting a tag finds that release"):
    assertEquals(Application.selectRelease(releases, tagged("v3.3.0")), Right(previous))

  test("selecting an unknown tag is NotFound with that tag"):
    assertEquals(
      Application.selectRelease(releases, tagged("v9.9.9")),
      Left(AppFailure.Release(ReleaseError.NotFound(tag("v9.9.9")))),
    )

  test("a destination that cannot be expanded is a Destination failure"):
    val d = loading().copy(expandDestination = _ => Left(PathError.NoHome))
    assertEquals(application(explicit, d), Left(AppFailure.Destination(PathError.NoHome)))

  test("an install failure is wrapped"):
    val cause = InstallError.Destination(os.Path("/tmp/fonts"), "permission denied")
    assertEquals(
      application(explicit, loading().copy(installFonts = (_, _) => Left(cause))),
      Left(AppFailure.Install(cause)),
    )

  test("an interrupt during the install is Interrupted in the install phase"):
    val d = loading().copy(installFonts = (_, _) => throw InterruptedException())
    assertEquals(application(explicit, d), Left(AppFailure.Interrupted(InterruptPhase.Install)))

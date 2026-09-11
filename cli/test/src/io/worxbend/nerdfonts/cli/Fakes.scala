package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.environment.PathExpander
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.picker.IconMode
import io.worxbend.nerdfonts.picker.PickerOutcome
import io.worxbend.nerdfonts.releases.Release

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Pure stand-ins for every seam in `AppDependencies`, plus a runner that captures both streams. The defaults
 * describe a machine with no config anywhere, a catalogue of two releases and no terminal, so each test
 * overrides only the seam it is about.
 */
private[cli] object Fakes:
  val cwd: os.Path  = os.Path("/workspace")
  val home: os.Path = os.Path("/home/test")

  def tag(value: String): ReleaseTag = ReleaseTag.parse(value).getOrElse(sys.error(s"blank tag $value"))

  def family(value: String): FamilyName = FamilyName.parse(value).getOrElse(sys.error(s"unsafe $value"))

  val latest: Release           = Release("v3.4.0", tag("v3.4.0"), Vector("Hack", "JetBrainsMono"))
  val previous: Release         = Release("v3.3.0", tag("v3.3.0"), Vector("FiraCode", "Meslo"))
  val releases: Vector[Release] = Vector(latest, previous)

  val hackConfig: InstallConfig = config(ReleaseSelector.Latest, "/tmp/fonts", "Hack")

  def config(selector: ReleaseSelector, destination: String, families: String*): InstallConfig =
    InstallConfig(
      selector,
      DestinationPath.parse(destination).getOrElse(sys.error("blank destination")),
      RefreshFontCache.Enabled,
      families.toVector.map(family),
    )

  def tagged(value: String): ReleaseSelector = ReleaseSelector.Tagged(tag(value))

  def environment(
      variables: Map[String, String] = Map.empty,
      properties: Map[String, String] = Map.empty,
  ): Environment = Environment.fixed(
    variables,
    homeDirectory = Some(home),
    workingDirectory = Right(cwd),
    properties = properties,
  )

  /** The candidate list discovery would search on the fake machine. */
  def candidates(env: Environment): Vector[os.Path] = ConfigLocations.candidates(env).getOrElse(Vector.empty)

  def deps(env: Environment = environment()): AppDependencies = AppDependencies(
    environment = env,
    colours = ColourMode.Plain,
    loadConfig = path => Left(ConfigError.NotFound(path)),
    discoverConfig = () => Right(None),
    configCandidates = () => candidates(env),
    listReleases = () => Right(releases),
    runPicker = (_, _, _) => Right(PickerOutcome.Cancelled),
    installFonts = (_, _) => Right(()),
    isTerminal = () => false,
    expandDestination = PathExpander.expand(_, env),
  )

  /** What one `Cli.run` produced. */
  final case class Run(code: Int, out: String, err: String)

  def run(deps: AppDependencies, args: String*): Run =
    val out  = StringWriter()
    val err  = StringWriter()
    val code = Cli.run(args.toArray, PrintWriter(out, true), PrintWriter(err, true), deps)
    Run(code, out.toString, err.toString)

  def options(
      explicitConfig: Option[String] = None,
      mode: CliMode = CliMode.Install,
      dryRun: DryRun = DryRun.Disabled,
      interactive: Interactive = Interactive.NotRequested,
      icons: IconMode = IconMode.Auto,
  ): CliOptions = CliOptions(explicitConfig, mode, dryRun, interactive, icons)

  /** `Application.run` against throwaway writers, for tests that only care about the returned value. */
  def application(options: CliOptions, deps: AppDependencies): Either[AppFailure, AppOutcome] =
    Application.run(options, deps, PrintWriter(StringWriter(), true), PrintWriter(StringWriter(), true))

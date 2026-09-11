package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.picker.IconMode

import java.io.PrintWriter
import java.nio.file.Path

import scala.annotation.unused

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Help
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParseResult

/**
 * The process boundary: picocli parsing, `--help`/`--version`, `--icons` validation, and the exit code.
 *
 * picocli is configured to behave like Go's `flag` package: every option has a single-dash spelling, parsing
 * stops at the first positional (which is ignored), and an unknown option is a usage error (exit 2). The one
 * deliberate deviation is `--help`, which goes to stdout with exit 0. `--icons` is validated before
 * `--version` is honoured, so `--version --icons bogus` exits 2 as the reference does; that ordering is why
 * the command runs through a custom execution strategy instead of picocli's `RunLast`. The strategy is also
 * where an interrupt is caught (§6.8), because nothing thrown out of it survives `CommandLine.execute` intact.
 */
object Cli:
  /** The production entry: real environment, JDK adapters, the system temp directory for downloads. */
  def run(args: Array[String], out: PrintWriter, err: PrintWriter): Int =
    run(args, out, err, AppDependencies.production(Environment.System, systemTempDir))

  def run(args: Array[String], out: PrintWriter, err: PrintWriter, deps: AppDependencies): Int =
    commandLine(deps, out, err).execute(args*)

  private def commandLine(deps: AppDependencies, out: PrintWriter, err: PrintWriter): CommandLine =
    val command     = RootCommand()
    val commandLine = CommandLine(command)
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine.setColorScheme(Help.defaultColorScheme(Help.Ansi.OFF))
    commandLine.setStopAtPositional(true)
    commandLine.getCommandSpec.versionProvider(VersionProvider)
    commandLine.setExecutionStrategy(parseResult => execute(command, parseResult, deps, out, err))
    commandLine

  // `executeHelpRequest` is what picocli's own strategies call first; running it after the icon check is
  // the whole reason for the custom strategy.
  private def execute(
      command: RootCommand,
      parseResult: ParseResult,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
  ): Int = IconMode.parse(command.rawIcons) match
    case Left(error)  =>
      err.println(error.render)
      ExitCode.usage
    case Right(icons) => Option(CommandLine.executeHelpRequest(parseResult))
        .map(_.intValue)
        .getOrElse(report(runApplication(command.options(icons), deps, out, err), err))

  // The SIGINT path of §6.8: an `InterruptedException` escaping the application means every `finally` below
  // has already run and the only thing left is to say so and exit 1. The conversion has to happen inside the
  // execution strategy because `CommandLine.execute` catches anything the strategy throws, prints a stack
  // trace and returns 1 (the code would be right, the output would not). An interrupt during the install
  // never reaches here: `Application.install` reports it with the `install fonts: ` prefix.
  private def runApplication(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
  ): Either[AppFailure, AppOutcome] = Interruptible.run(Application.run(options, deps, out, err)) match
    case Right(result) => result
    case Left(_)       => Left(AppFailure.Interrupted(InterruptPhase.BeforeInstall))

  private def report(result: Either[AppFailure, AppOutcome], err: PrintWriter): Int =
    result.left.foreach(failure => err.println(failure.render))
    ExitCode.of(result)

  // The composition root is the one place allowed to read a system property; `java.io.tmpdir` is where the
  // JDK itself would put a temp file, made absolute in case the property is relative.
  private def systemTempDir: os.Path = os.Path(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath)

/**
 * The picocli root command. It does nothing but collect option values; picocli binds them by calling the
 * annotated setters, which is why this is the one class in the codebase allowed a `var` (SPEC §2). The
 * `--icons` value is kept raw because the reference validates it after parsing and quotes the original text.
 */
@Command(
  name = "nerd-fonts-installer",
  header = Array("Nerd Fonts, installed the boring way."),
  description = Array("Install Nerd Fonts from a config file or an interactive picker."),
  sortOptions = false,
  mixinStandardHelpOptions = false,
)
@SuppressWarnings(
  Array("scalafix:DisableSyntax.var"),
) // picocli binds options through setters into this one field.
final private[cli] class RootCommand:
  private var draft: OptionDraft = OptionDraft.defaults

  @CliOption(
    names = Array("-config", "--config"),
    paramLabel = "<path>",
    description = Array(
      "config file; when omitted, discover an app-named config in CWD or the user config directory",
    ),
  )
  def setConfig(value: String): Unit = draft = draft.copy(explicitConfig = Some(value))

  @CliOption(
    names = Array("-dry-run", "--dry-run"),
    description = Array("print planned downloads without installing fonts"),
  )
  def setDryRun(value: Boolean): Unit = draft = draft.copy(dryRun = DryRun.fromBoolean(value))

  @CliOption(
    names = Array("-font-names", "--font-names"),
    description = Array("print YAML-ready Nerd Font family names and exit"),
  )
  def setFontNames(value: Boolean): Unit =
    draft = draft.copy(mode = if value then CliMode.FontNames else CliMode.Install)

  @CliOption(
    names = Array("-interactive", "--interactive"),
    description = Array("start the terminal picker when no config file is found"),
  )
  def setInteractive(value: Boolean): Unit = draft = draft.copy(interactive = Interactive.fromBoolean(value))

  @CliOption(
    names = Array("-icons", "--icons"),
    paramLabel = "<mode>",
    description = Array("interactive icon mode: auto, nerd, unicode, or ascii (default: auto)"),
  )
  def setIcons(value: String): Unit = draft = draft.copy(icons = value)

  @CliOption(
    names = Array("-version", "--version"),
    versionHelp = true,
    description = Array("print version information and exit"),
  )
  def setVersion(@unused value: Boolean): Unit = ()

  @CliOption(
    names = Array("-h", "-help", "--help"),
    usageHelp = true,
    description = Array("print this help and exit"),
  )
  def setHelp(@unused value: Boolean): Unit = ()

  // Go's `flag` stops at the first positional and leaves the rest to the caller, which ignores them.
  @Parameters(arity = "0..*", hidden = true)
  def setIgnored(@unused values: Array[String]): Unit = ()

  def rawIcons: String = draft.icons

  def options(icons: IconMode): CliOptions =
    CliOptions(draft.explicitConfig, draft.mode, draft.dryRun, draft.interactive, icons)

/** The values picocli has bound so far; the Go flag defaults until a setter runs. */
final private[cli] case class OptionDraft(
    explicitConfig: Option[String],
    mode: CliMode,
    dryRun: DryRun,
    interactive: Interactive,
    icons: String,
)

private[cli] object OptionDraft:
  val defaults: OptionDraft =
    OptionDraft(None, CliMode.Install, DryRun.Disabled, Interactive.NotRequested, IconMode.Auto.render)

/** Go's `"%s %s (%s, %s)\n"` from the constants Mill generated; wired programmatically, so never reflected on. */
private[cli] object VersionProvider extends CommandLine.IVersionProvider:
  val line: String =
    s"nerd-fonts-installer ${BuildInfo.version} (${BuildInfo.commit}, ${BuildInfo.buildDate})"

  def getVersion(): Array[String] = Array(line)

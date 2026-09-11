package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.picker.IconMode

import java.io.PrintWriter
import java.nio.file.Path

import ox.pipe
import picocli.CommandLine
import picocli.CommandLine.Help
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
    run(args, out, err, AppDependencies.production(Environment.System, tempDir(Environment.System)))

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

  // Go's `os.CreateTemp("", …)` honours `$TMPDIR`; the JDK's `java.io.tmpdir` is fixed at `/tmp` on Linux, so
  // the variable is consulted first and the property is only the fallback. The composition root is the one
  // place allowed to read a system property; the path is made absolute in case either value is relative.
  private[cli] def tempDir(env: Environment): os.Path = env
    .variable(Cli.tempDirVariable)
    .filter(_.nonEmpty)
    .getOrElse(System.getProperty("java.io.tmpdir"))
    .pipe(raw => os.Path(Path.of(raw).toAbsolutePath))

  private val tempDirVariable = "TMPDIR"

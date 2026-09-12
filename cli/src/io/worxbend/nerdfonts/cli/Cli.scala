package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.environment.Environment

import java.io.PrintWriter
import java.nio.file.Path

import zio.Scope
import zio.UIO
import zio.ZIO

/**
 * The process boundary: hand-rolled parsing, `--help`/`--version`, and the exit code.
 *
 * `--help` prints usage to **stdout** and exits 0 because it is a *requested, successful* output; a usage
 * error is different and goes to **stderr** with exit 2. `--version` also prints to stdout and exits 0; an
 * unknown flag or a single-dash long flag prints a message plus the usage to stderr and exits 2 (SPEC §7).
 * Only the install and font-names paths build the production dependencies, so `--help`/`--version`/a usage
 * error never start a Netty event loop.
 *
 * The whole boundary is a `ZIO` value that `app.Main` (`ZIOAppDefault`) executes. Nothing here returns an
 * `ExitCode` value to the runtime: the exit code is computed and `Main` calls `exit`, because returning an
 * `ExitCode` from `run` leaves the process exiting 0.
 */
object Cli:
  /** The production entry: real environment, the system temp directory for downloads, the hardened HTTP client. */
  def run(args: Array[String], out: PrintWriter, err: PrintWriter): ZIO[Scope, Nothing, Int] =
    ArgumentParser.parse(args.toList) match
      case ParseResult.Options(options) =>
        for
          tempDirectory <- tempDir(Environment.System)
          deps          <- AppDependencies.production(Environment.System, tempDirectory).orDie
          code          <- runApplication(options, deps, out, err)
        yield code
      case terminal                     => ZIO.succeed(report(terminal, out, err))

  /** The test/direct entry: dependencies are supplied, so no HTTP client is built and no network is touched. */
  def run(args: Array[String], out: PrintWriter, err: PrintWriter, deps: AppDependencies): UIO[Int] =
    ArgumentParser.parse(args.toList) match
      case ParseResult.Options(options) => runApplication(options, deps, out, err)
      case terminal                     => ZIO.succeed(report(terminal, out, err))

  // The interrupt-to-value conversion and this reporting must share one uninterruptible region: an external
  // SIGINT (real `Fiber.interrupt`) is sticky, so if reporting ran interruptibly the re-fired interrupt would
  // skip the `err.println` and the process would exit with no message. `Application.run` re-enables
  // interruption only for the work via `restore`, absorbs the interrupt into a plain `Left`, and the `map`
  // below then prints and computes the code while still uninterruptible.
  private def runApplication(
      options: CliOptions,
      deps: AppDependencies,
      out: PrintWriter,
      err: PrintWriter,
  ): UIO[Int] = ZIO.uninterruptibleMask: restore =>
    Application
      .run(options, deps, out, err, restore)
      .map: result =>
        result.left.foreach(failure => err.println(failure.render))
        ExitCode.of(result)

  // `--help`/`--version` go to stdout with exit 0; a usage error prints the message and the usage to stderr
  // with exit 2. The `Options` case never reaches here.
  private def report(result: ParseResult, out: PrintWriter, err: PrintWriter): Int = result match
    case ParseResult.ShowHelp       =>
      out.print(usageText)
      ExitCode.success
    case ParseResult.ShowVersion    =>
      out.println(VersionProvider.line)
      ExitCode.success
    case ParseResult.Usage(message) =>
      err.println(message)
      err.print(usageText)
      ExitCode.usage
    case ParseResult.Options(_)     => ExitCode.success

  // Downloads honour `$TMPDIR`, falling back to `/tmp` on Unix when it is unset; the JDK's `java.io.tmpdir`
  // is the same `/tmp` on Linux, so it is consulted next and the hard-coded `/tmp` is only a
  // last resort for a JVM that somehow has neither. Both are read through the `Environment` port (invariant
  // 7): nothing below the composition root reads `sys.env`/`sys.props`, and this is the composition root
  // itself. The path is made absolute in case any of the three is relative.
  private[cli] def tempDir(env: Environment): UIO[os.Path] =
    for
      variable <- env.variable(tempDirVariable)
      property <- env.property(tempDirProperty)
    yield
      val raw = variable.filter(_.nonEmpty).orElse(property).getOrElse("/tmp")
      os.Path(Path.of(raw).toAbsolutePath)

  private val tempDirVariable = "TMPDIR"
  private val tempDirProperty = "java.io.tmpdir"

  /**
   * The usage text, printed for `--help` and appended after a usage-error message. The options are listed
   * alphabetically (`config, dry-run, font-names, version`) with `--help` last. Only the double-dash spelling
   * of each is shown; `-h` and `-help` remain accepted as help aliases, so both appear on the help line.
   */
  private[cli] val usageText: String = Vector(
    "Nerd Fonts, installed the boring way.",
    "Usage: nerd-fonts-installer [flags]",
    "",
    "Install Nerd Fonts from a config file.",
    "",
    "Flags:",
    "      --config <path>   config file; when omitted, discover an app-named config in the working directory or the user config directory",
    "      --dry-run         print planned downloads without installing fonts",
    "      --font-names      print YAML-ready Nerd Font family names and exit",
    "      --version         print version information and exit",
    "  -h, -help, --help     print this help and exit",
    "",
  ).mkString("\n")

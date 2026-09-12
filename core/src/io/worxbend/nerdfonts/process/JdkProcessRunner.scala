package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.environment.Environment

import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.Try

import zio.IO
import zio.UIO
import zio.ZIO

/**
 * The production `ProcessRunner` over `java.lang.ProcessBuilder`.
 *
 * `PATH` is read through the `Environment` port rather than `sys.env` so lookups are testable and so the
 * "not installed" case is decided before the JDK turns it into an opaque `IOException`. On interruption the
 * child is destroyed and reaped, with a bounded wait, before the interruption completes, so an
 * interrupted run never leaves a live subprocess behind.
 */
final class JdkProcessRunner(env: Environment) extends ProcessRunner:
  // The resolved path is used as argv[0] (`builder`) rather than the bare program name, so `ProcessBuilder`
  // never performs its own PATH search and cannot disagree with `lookPath` about which binary runs.
  def run(spec: ProcessSpec): IO[ProcessError, ProcessResult] =
    if spec.command.isEmpty then ZIO.fail(ProcessError.Failed("", "empty command"))
    else
      lookPath(spec.program).flatMap:
        case None           => ZIO.fail(ProcessError.NotFound(spec.program))
        case Some(resolved) => start(spec, resolved).flatMap(await(_, spec.stdout))

  def lookPath(name: String): UIO[Option[os.Path]] =
    if name.contains('/') then resolveDirect(name).flatMap(filterExecutable)
    else
      searchPath.flatMap(paths =>
        ZIO.attemptBlockingIO(paths.map(_ / os.RelPath(name)).find(isExecutableFile)).orDie,
      )

  private def filterExecutable(candidate: Option[os.Path]): UIO[Option[os.Path]] = candidate match
    case None       => ZIO.none
    case Some(path) => ZIO.attemptBlockingIO(Option.when(isExecutableFile(path))(path)).orDie

  private def start(spec: ProcessSpec, resolved: os.Path): IO[ProcessError, Process] = ZIO
    .attemptBlockingIO(builder(spec, resolved).start())
    .mapError(error => ProcessError.Failed(spec.program, Diagnostics.describe(error)))

  private def builder(spec: ProcessSpec, resolved: os.Path): ProcessBuilder =
    ProcessBuilder((resolved.toString +: spec.command.tail).asJava)
      .redirectInput(redirectInput(spec.stdin))
      .redirectOutput(redirectOutput(spec.stdout))
      .redirectError(redirectError(spec.stderr))

  private def redirectInput(stdin: Stdin): Redirect = stdin match
    case Stdin.Inherit        => Redirect.INHERIT
    case Stdin.FromFile(path) => Redirect.from(path.toIO)

  private def redirectOutput(stdout: Stdout): Redirect = stdout match
    case Stdout.Inherit => Redirect.INHERIT
    case Stdout.Capture => Redirect.PIPE

  private def redirectError(stderr: Stderr): Redirect = stderr match
    case Stderr.Inherit => Redirect.INHERIT
    case Stderr.Discard => Redirect.DISCARD

  // Stdout is drained before waiting so a chatty child can never fill the pipe and deadlock against us. The
  // drain is a classic pipe read, which ignores fiber/thread interruption (a background-process-group `stty`
  // blocked on `tcsetattr` after SIGTTOU is the case that matters): it runs on a forked fiber that is never
  // joined if the caller is interrupted first, and interrupting the join (a pure ZIO await, always
  // interruptible) destroys the process instead, which EOFs the pipe and lets the forked fiber finish on its
  // own. `waitFor` itself is genuinely interruptible: unlike the pipe read, the JDK implements it so that
  // `Thread.interrupt()` unblocks it with an `InterruptedException`, so `attemptBlockingInterrupt` (which
  // calls that on fiber interruption, unlike plain `attemptBlockingIO`) is what makes this call return
  // promptly instead of riding out the child's remaining lifetime. The interruption itself still completes
  // once `destroyAndReap` below has run.
  private def await(process: Process, stdout: Stdout): IO[ProcessError, ProcessResult] =
    drain(process, stdout)
      .flatMap(captured =>
        ZIO.attemptBlockingInterrupt(ExitStatus.of(process.waitFor())).orDie.map(ProcessResult(_, captured)),
      )
      .ensuring(destroyAndReap(process))

  private def drain(process: Process, stdout: Stdout): UIO[String] = stdout match
    case Stdout.Capture =>
      for
        fiber    <- ZIO
                      .attemptBlockingIO(String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8))
                      .orDie
                      .fork
        captured <- fiber.join.onInterrupt(ZIO.succeed(process.destroyForcibly()).unit)
      yield captured
    case Stdout.Inherit => ZIO.succeed("")

  // SIGKILLs a still-running child (SIGTERM alone can be ignored) and waits, bounded and uninterruptibly, for
  // it to exit before the interruption that triggered this completes. A bare `destroy()` returns immediately,
  // so the caller (and the process it exits to) could otherwise observe "interrupted" while the child is
  // still alive, sharing the terminal or racing a caller that inspects the destination right after `run`
  // returns.
  private def destroyAndReap(process: Process): UIO[Unit] = ZIO
    .attemptBlockingIO(process.isAlive)
    .orDie
    .flatMap: alive =>
      if !alive then ZIO.unit
      else
        (ZIO.succeed(process.destroyForcibly()) *>
          ZIO
            .attemptBlockingIO(process.waitFor(JdkProcessRunner.destroyReapSeconds, TimeUnit.SECONDS))
            .orDie
            .unit).uninterruptible

  // A `PATH` search must never resolve a binary relative to the current directory: the resolved binary
  // would then depend on the working directory and be trivially hijackable. An empty entry is exactly that
  // case (`.` to a shell), and so is a bare relative entry (`sub/dir`). Only absolute entries are searched,
  // so a leading/trailing `:` or a `.` on `PATH` can never make this resolve `fc-cache` or `stty` to a
  // binary the user did not put there.
  private def searchPath: UIO[Vector[os.Path]] = env
    .variable("PATH")
    .map(
      _.toVector
        .flatMap(_.split(java.io.File.pathSeparatorChar).toVector)
        .filter(_.startsWith("/"))
        .flatMap(entry => Try(os.Path(entry)).toOption),
    )

  // A name containing a slash is used directly: no `PATH` search, so the relative-resolution guard above
  // does not apply, and a relative name resolves against the working directory because the caller named
  // that path explicitly rather than PATH resolving into it by surprise.
  private def resolveDirect(name: String): UIO[Option[os.Path]] =
    if name.startsWith("/") then ZIO.succeed(Try(os.Path(name)).toOption)
    else env.workingDirectory.option.map(_.flatMap(cwd => Try(os.Path(name, cwd)).toOption))

  private def isExecutableFile(path: os.Path): Boolean =
    Files.isRegularFile(path.toNIO) && Files.isExecutable(path.toNIO)

object JdkProcessRunner:
  /** How long `destroyAndReap` waits for a forcibly-destroyed child to exit before giving up on the reap. */
  private val destroyReapSeconds: Long = 2L

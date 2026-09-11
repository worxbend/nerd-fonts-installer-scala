package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.environment.Environment

import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.Try

import ox.abandonOnInterrupt
import ox.discard
import ox.either.catching
import ox.uninterruptible

/**
 * The production `ProcessRunner` over `java.lang.ProcessBuilder`.
 *
 * `PATH` is read through the `Environment` port rather than `sys.env` so lookups are testable and so the
 * "not installed" case is decided before the JDK turns it into an opaque `IOException`. On interruption the
 * child is destroyed and reaped, bounded, before the `InterruptedException` propagates, which is what Go's
 * `exec.CommandContext` does when the root context is cancelled.
 */
final class JdkProcessRunner(env: Environment) extends ProcessRunner:
  // The resolved path is used as argv[0] (`builder`) rather than the bare program name, so `ProcessBuilder`
  // never performs its own PATH search and cannot disagree with `lookPath` about which binary runs.
  def run(spec: ProcessSpec): Either[ProcessError, ProcessResult] =
    if spec.command.isEmpty then Left(ProcessError.Failed("", "empty command"))
    else lookPath(spec.program) match
      case None           => Left(ProcessError.NotFound(spec.program))
      case Some(resolved) => start(spec, resolved).map(await(_, spec.stdout))

  def lookPath(name: String): Option[os.Path] =
    if name.contains('/') then Some(name).flatMap(resolveDirect).filter(isExecutableFile)
    else searchPath.iterator.map(_ / os.RelPath(name)).find(isExecutableFile)

  private def start(spec: ProcessSpec, resolved: os.Path): Either[ProcessError, Process] =
    builder(spec, resolved)
      .start()
      .catching[IOException]
      .left
      .map: error =>
        ProcessError.Failed(spec.program, Diagnostics.describe(error))

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
  // drain is a classic pipe read, which ignores `Thread.interrupt` (a background-process-group `stty` blocked
  // on `tcsetattr` after SIGTTOU is the case that matters): `abandonOnInterrupt` moves it to a detached thread
  // and, on abandonment, destroys the process, which EOFs the pipe and lets that thread finish. The interrupt
  // itself still propagates once `destroyAndReap` below has run.
  private def await(process: Process, stdout: Stdout): ProcessResult =
    try
      val captured = stdout match
        case Stdout.Capture =>
          abandonOnInterrupt(String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)):
            process.destroyForcibly().discard
        case Stdout.Inherit => ""
      ProcessResult(ExitStatus.of(process.waitFor()), captured)
    finally destroyAndReap(process)

  // SIGKILLs a still-running child (SIGTERM alone can be ignored) and waits, bounded, for it to exit before
  // the interrupt that triggered this propagates. A bare `destroy()` returns immediately, so the caller (and
  // the process it exits to) could otherwise observe "interrupted" while the child is still alive, sharing
  // the terminal or racing a caller that inspects the destination right after `run` returns.
  private def destroyAndReap(process: Process): Unit =
    if process.isAlive then
      process.destroyForcibly()
      uninterruptible(process.waitFor(JdkProcessRunner.destroyReapSeconds, TimeUnit.SECONDS)).discard

  // Go (since 1.19, `exec.ErrDot`) refuses to run a binary that a `PATH` search resolved relative to the
  // current directory; an empty entry is exactly that case (`.` to a shell), and so is a bare relative entry
  // (`sub/dir`). Only absolute entries are searched, so a leading/trailing `:` or a `.` on `PATH` can never
  // make this resolve `fc-cache` or `stty` to a binary the user did not put there.
  private def searchPath: Vector[os.Path] =
    env
      .variable("PATH")
      .toVector
      .flatMap(_.split(java.io.File.pathSeparatorChar).toVector)
      .filter(_.startsWith("/"))
      .flatMap(entry => Try(os.Path(entry)).toOption)

  // A name containing a slash is used directly, exactly as Go's `exec.LookPath` does: no `PATH` search, so
  // `ErrDot` does not apply, and a relative name resolves against the working directory because the caller
  // named that path explicitly rather than PATH resolving into it by surprise.
  private def resolveDirect(name: String): Option[os.Path] =
    if name.startsWith("/") then Try(os.Path(name)).toOption
    else env.workingDirectory.toOption.flatMap(cwd => Try(os.Path(name, cwd)).toOption)

  private def isExecutableFile(path: os.Path): Boolean =
    Files.isRegularFile(path.toNIO) && Files.isExecutable(path.toNIO)

object JdkProcessRunner:
  /** How long `destroyAndReap` waits for a forcibly-destroyed child to exit before giving up on the reap. */
  private val destroyReapSeconds: Long = 2L

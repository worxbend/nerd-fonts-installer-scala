package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.environment.Environment

import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters.*
import scala.util.Try

import ox.either.catching

/**
 * The production `ProcessRunner` over `java.lang.ProcessBuilder`.
 *
 * `PATH` is read through the `Environment` port rather than `sys.env` so lookups are testable and so the
 * "not installed" case is decided before the JDK turns it into an opaque `IOException`. On interruption the
 * child is destroyed before the `InterruptedException` propagates, which is what Go's
 * `exec.CommandContext` does when the root context is cancelled.
 */
final class JdkProcessRunner(env: Environment) extends ProcessRunner:
  def run(spec: ProcessSpec): Either[ProcessError, ProcessResult] =
    if spec.command.isEmpty then Left(ProcessError.Failed("", "empty command"))
    else if !isInstalled(spec.program) then Left(ProcessError.NotFound(spec.program))
    else start(spec).map(await(_, spec.stdout))

  def lookPath(name: String): Option[os.Path] =
    if name.contains('/') then Some(name).flatMap(asPath).filter(isExecutableFile)
    else searchPath.iterator.map(_ / os.RelPath(name)).find(isExecutableFile)

  private def isInstalled(program: String): Boolean = lookPath(program).isDefined

  private def start(spec: ProcessSpec): Either[ProcessError, Process] = builder(spec)
    .start()
    .catching[IOException]
    .left
    .map: error =>
      ProcessError.Failed(spec.program, Diagnostics.describe(error))

  private def builder(spec: ProcessSpec): ProcessBuilder = ProcessBuilder(spec.command.asJava)
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

  // Stdout is drained before waiting so a chatty child can never fill the pipe and deadlock against us.
  private def await(process: Process, stdout: Stdout): ProcessResult =
    try
      val captured = stdout match
        case Stdout.Capture => String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        case Stdout.Inherit => ""
      ProcessResult(ExitStatus.of(process.waitFor()), captured)
    finally if process.isAlive then process.destroy()

  private def searchPath: Vector[os.Path] =
    env.variable("PATH").toVector.flatMap(_.split(java.io.File.pathSeparatorChar).toVector).flatMap(asPath)

  // An empty `PATH` entry means the working directory, to Go and the shell alike; only relative entries
  // need it, so a missing working directory cannot break lookups on an all-absolute `PATH`.
  private def asPath(entry: String): Option[os.Path] =
    val raw = if entry.isEmpty then "." else entry
    if raw.startsWith("/") then Try(os.Path(raw)).toOption
    else env.workingDirectory.toOption.flatMap(cwd => Try(os.Path(raw, cwd)).toOption)

  private def isExecutableFile(path: os.Path): Boolean =
    Files.isRegularFile(path.toNIO) && Files.isExecutable(path.toNIO)

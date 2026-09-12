package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.discard

import zio.IO
import zio.UIO
import zio.ZIO

import java.util.concurrent.atomic.AtomicReference

/**
 * A scripted `ProcessRunner` for tests in every module: the first script whose `prefix` matches the start
 * of the command answers, every call is recorded, and `lookPath` answers from a fixed table so a test can
 * make `fc-cache` or `stty` appear installed or missing without touching `PATH`.
 */
final class FakeProcessRunner(
    scripts: Vector[FakeProcessRunner.Script] = Vector.empty,
    executables: Map[String, os.Path] = Map.empty,
) extends ProcessRunner:
  private val recorded = AtomicReference(Vector.empty[ProcessSpec])

  /** Every spec passed to `run`, in call order. */
  def calls: Vector[ProcessSpec] = recorded.get()

  def run(spec: ProcessSpec): IO[ProcessError, ProcessResult] = ZIO.succeed(
    recorded.updateAndGet(_ :+ spec).discard,
  ) *> ZIO.fromEither(
    scripts
      .find(script => spec.command.startsWith(script.prefix))
      .map(_.result)
      .getOrElse(Left(ProcessError.NotFound(spec.program))),
  )

  def lookPath(name: String): UIO[Option[os.Path]] = ZIO.succeed(executables.get(name))

object FakeProcessRunner:
  /** One canned answer: applies to every command that starts with `prefix`. */
  final case class Script(prefix: Vector[String], result: Either[ProcessError, ProcessResult])

  object Script:
    def succeeding(prefix: Vector[String], stdout: String = ""): Script =
      Script(prefix, Right(ProcessResult(ExitStatus.success, stdout)))

    def exiting(prefix: Vector[String], code: Int, stdout: String = ""): Script =
      Script(prefix, Right(ProcessResult(ExitStatus.of(code), stdout)))

    def failing(prefix: Vector[String], error: ProcessError): Script = Script(prefix, Left(error))

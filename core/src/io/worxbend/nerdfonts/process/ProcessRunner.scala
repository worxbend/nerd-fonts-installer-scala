package io.worxbend.nerdfonts.process

import zio.IO
import zio.UIO

/**
 * The one way this application starts a subprocess (`fc-cache`, `stty`). Everything goes through an argv
 * vector, never a shell, so a destination path containing `$` or a space cannot become shell syntax.
 */
trait ProcessRunner:
  def run(spec: ProcessSpec): IO[ProcessError, ProcessResult]

  /** Where `name` would resolve on `PATH`, or `None` when it is not installed. */
  def lookPath(name: String): UIO[Option[os.Path]]

/**
 * What to run and how its three streams are wired. The defaults inherit the parent's streams, which is
 * what lets `fc-cache` print straight to the user's terminal.
 */
final case class ProcessSpec(
    command: Vector[String],
    stdin: Stdin = Stdin.Inherit,
    stdout: Stdout = Stdout.Inherit,
    stderr: Stderr = Stderr.Inherit,
):
  /** The program name for messages; empty when the spec is (mis)constructed without one. */
  def program: String = command.headOption.getOrElse("")

/** Where a child's stdin comes from; `FromFile` exists for `stty`, which must talk to `/dev/tty`. */
enum Stdin:
  case Inherit
  case FromFile(path: os.Path)

/** Whether a child's stdout is shown to the user or captured into `ProcessResult.stdout`. */
enum Stdout:
  case Inherit, Capture

/** Whether a child's stderr is shown to the user or dropped. */
enum Stderr:
  case Inherit, Discard

/** How a finished child ended; `stdout` is empty unless the spec asked to capture it. */
final case class ProcessResult(exit: ExitStatus, stdout: String)

/** A child's exit code, rendered as `exit status N` in messages. */
opaque type ExitStatus = Int

object ExitStatus:
  val success: ExitStatus = 0

  def of(code: Int): ExitStatus = code

  extension (status: ExitStatus)
    def code: Int          = status
    def isSuccess: Boolean = status == 0
    def render: String     = s"exit status $status"

/** Why a child could not be run at all; a non-zero exit is a `ProcessResult`, not an error. */
enum ProcessError:
  case NotFound(command: String)
  case Failed(command: String, cause: String)

  def render: String = this match
    case NotFound(command)      => s"$command: executable file not found in PATH"
    case Failed(command, cause) => s"$command: $cause"

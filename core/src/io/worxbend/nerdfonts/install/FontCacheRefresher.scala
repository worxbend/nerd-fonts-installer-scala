package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.process.ExitStatus
import io.worxbend.nerdfonts.process.ProcessError
import io.worxbend.nerdfonts.process.ProcessRunner
import io.worxbend.nerdfonts.process.ProcessSpec

/**
 * The font cache as a port, so the engine can be tested without `fc-cache` and so the "not installed" case
 * is a value the engine turns into a warning rather than a failure (Go: `exec.LookPath` before `Run`).
 */
trait FontCacheRefresher:
  def availability: FontCacheAvailability

  def refresh(root: os.Path): Either[FontCacheError, Unit]

/** Whether `fc-cache` can be run at all; a two-case enum so the engine cannot misread a bare flag. */
enum FontCacheAvailability:
  case Available, Unavailable

/** Why `fc-cache` did not refresh the cache; rendered after the engine's `run fc-cache for <root>: ` prefix. */
enum FontCacheError:
  case Launch(cause: ProcessError)
  case Exit(status: ExitStatus)

  def render: String = this match
    case Launch(cause) => cause.render
    case Exit(status)  => status.render

/**
 * The production refresher: `fc-cache -f <root>` through the `ProcessRunner` port with every stream
 * inherited, so its output reaches the user's terminal exactly as under Go's `exec.Command`.
 */
final class FcCacheRefresher(processes: ProcessRunner) extends FontCacheRefresher:
  def availability: FontCacheAvailability =
    if processes.lookPath(FcCacheRefresher.program).isDefined then FontCacheAvailability.Available
    else FontCacheAvailability.Unavailable

  def refresh(root: os.Path): Either[FontCacheError, Unit] =
    processes.run(ProcessSpec(Vector(FcCacheRefresher.program, "-f", root.toString))) match
      case Left(error)                            => Left(FontCacheError.Launch(error))
      case Right(result) if result.exit.isSuccess => Right(())
      case Right(result)                          => Left(FontCacheError.Exit(result.exit))

object FcCacheRefresher:
  val program: String = "fc-cache"

package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.process.ExitStatus
import io.worxbend.nerdfonts.process.ProcessError
import io.worxbend.nerdfonts.process.ProcessRunner
import io.worxbend.nerdfonts.process.ProcessSpec

import zio.IO
import zio.UIO
import zio.ZIO

/**
 * The font cache as a port, so the engine can be tested without `fc-cache` and so the "not installed" case
 * is a value the engine turns into a warning rather than a failure (Go: `exec.LookPath` before `Run`).
 */
trait FontCacheRefresher:
  def availability: UIO[FontCacheAvailability]

  def refresh(root: os.Path): IO[FontCacheError, Unit]

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
  def availability: UIO[FontCacheAvailability] = processes
    .lookPath(FcCacheRefresher.program)
    .map(resolved =>
      if resolved.isDefined then FontCacheAvailability.Available else FontCacheAvailability.Unavailable,
    )

  def refresh(root: os.Path): IO[FontCacheError, Unit] = processes
    .run(ProcessSpec(Vector(FcCacheRefresher.program, "-f", root.toString)))
    .mapError(FontCacheError.Launch(_))
    .flatMap(result => if result.exit.isSuccess then ZIO.unit else ZIO.fail(FontCacheError.Exit(result.exit)))

object FcCacheRefresher:
  val program: String = "fc-cache"

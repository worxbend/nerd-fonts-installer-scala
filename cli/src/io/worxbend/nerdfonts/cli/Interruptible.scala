package io.worxbend.nerdfonts.cli

/**
 * The only `try`/`catch` for `InterruptedException` in the codebase (§6.8).
 *
 * `core` and `picker` let the exception propagate so Ox scopes unwind and every `finally` runs; the CLI is
 * where it stops (`Cli`'s execution strategy and `Application.install`). Ox's `.catching` deliberately excludes it (`InterruptedException` is fatal to
 * `scala.util.control.NonFatal`), so the boundary needs its own catch. The interrupt flag is left cleared:
 * both callers only print a message and return an exit code, and nothing blocking runs after that.
 */
private[cli] object Interruptible:
  def run[A](body: => A): Either[InterruptedException, A] =
    try Right(body)
    catch case interrupted: InterruptedException => Left(interrupted)

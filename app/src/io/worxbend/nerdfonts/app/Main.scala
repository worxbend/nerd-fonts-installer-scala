package io.worxbend.nerdfonts.app

import io.worxbend.nerdfonts.cli.Cli

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean

import sun.misc.Signal
import sun.misc.SignalHandler
import zio.ExitCode
import zio.Fiber
import zio.Runtime
import zio.Scope
import zio.Trace
import zio.Unsafe
import zio.ZIO
import zio.ZIOAppArgs
import zio.ZIOAppDefault

/**
 * JVM and native-image entry point; all behaviour lives in the CLI module.
 *
 * SIGINT is turned into an interrupt of the fiber running the application rather than the JVM's default
 * exit-130, because that default does not unwind finalizers (and native-image is simply killed): a
 * half-downloaded `nerd-font-*.zip` and a `<root>/.<Family>-*` staging directory would be left behind. ZIO
 * fiber interruption ends every `.ensuring`/`ZIO.acquireRelease` finalizer first, then `Cli.run` reports the
 * interrupt and returns exit 1, which is what Go's cancelled context achieves. A second SIGINT while that is in
 * progress halts the process outright with 130, the one escape hatch the reference does not offer (SPEC §6.8).
 *
 * The exit code is produced by calling `exit`, not by returning an `ExitCode` value: a returned value leaves
 * the process exiting 0.
 */
object Main extends ZIOAppDefault:
  /** The conventional 128 + SIGINT status, used only for the forced second-signal halt. */
  private val secondInterruptExitCode = 130

  def run: ZIO[ZIOAppArgs & Scope, Any, Unit] =
    for
      args    <- getArgs
      out      = PrintWriter(System.out, true)
      err      = PrintWriter(System.err, true)
      runtime <- ZIO.runtime[Any]
      fiber   <- Cli.run(args.toArray, out, err).fork
      _       <- installInterruptHandler(runtime, fiber)
      code    <- fiber.join
      _       <- ZIO.succeed:
                   out.flush()
                   err.flush()
      _       <- exit(ExitCode(code))
    yield ()

  // The signal handler runs on a bare OS thread and cannot run a ZIO effect directly, so it schedules the
  // interrupt on the runtime. The first SIGINT interrupts the application fiber (unwinding its finalizers); a
  // second, delivered while the first is still unwinding, halts the JVM immediately.
  private def installInterruptHandler(
      runtime: Runtime[Any],
      fiber: Fiber.Runtime[Nothing, Int],
  ): ZIO[Any, Nothing, Unit] = ZIO.succeed:
    val alreadyInterrupted     = AtomicBoolean(false)
    val handler: SignalHandler = _ =>
      if alreadyInterrupted.getAndSet(true) then java.lang.Runtime.getRuntime.halt(secondInterruptExitCode)
      else
        Unsafe.unsafe: unsafe =>
          val _ = runtime.unsafe.fork(fiber.interrupt)(using Trace.empty, unsafe)
    val _                      = Signal.handle(Signal("INT"), handler)

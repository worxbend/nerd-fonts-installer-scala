package io.worxbend.nerdfonts.app

import io.worxbend.nerdfonts.cli.Cli

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean

import ox.discard
import sun.misc.Signal

/**
 * JVM and native-image entry point; all behaviour lives in the CLI module.
 *
 * SIGINT is turned into an interrupt of the main thread instead of the JVM's default exit-130, because that
 * default does not unwind `finally` blocks (and native-image is simply killed): a half-downloaded
 * `nerd-font-*.zip` and a `<root>/.<Family>-*` staging directory would be left behind. Interrupting the main
 * thread ends the Ox scopes, runs every cleanup, restores the terminal and exits 1 through `Cli.run`, which is
 * what Go's cancelled context achieves. A second SIGINT while that is in progress halts the process outright,
 * the one escape hatch the reference does not offer.
 */
object Main:
  /** The conventional 128 + SIGINT status, used only for the forced second-signal halt. */
  private val secondInterruptExitCode = 130

  def main(args: Array[String]): Unit =
    val out      = PrintWriter(System.out, true)
    val err      = PrintWriter(System.err, true)
    installInterruptHandler(Thread.currentThread())
    val exitCode = Cli.run(args, out, err)
    out.flush()
    err.flush()
    sys.exit(exitCode)

  private def installInterruptHandler(mainThread: Thread): Unit =
    val alreadyInterrupted = AtomicBoolean(false)
    Signal
      .handle(
        Signal("INT"),
        _ =>
          if alreadyInterrupted.getAndSet(true) then Runtime.getRuntime.halt(secondInterruptExitCode)
          else mainThread.interrupt(),
      )
      .discard

package io.worxbend.nerdfonts.app

import io.worxbend.nerdfonts.cli.Cli

import java.io.PrintWriter

/** JVM and native-image entry point; all behaviour lives in the CLI module. */
object Main:
  def main(args: Array[String]): Unit =
    val out      = PrintWriter(System.out, true)
    val err      = PrintWriter(System.err, true)
    val exitCode = Cli.run(args, out, err)
    out.flush()
    err.flush()
    if exitCode != 0 then sys.exit(exitCode)

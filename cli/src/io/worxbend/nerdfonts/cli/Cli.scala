package io.worxbend.nerdfonts.cli

import java.io.PrintWriter

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option as CliOption

/** Temporary command surface used only to validate the toolchain. */
object Cli:
  def run(args: Array[String], out: PrintWriter, err: PrintWriter): Int =
    val commandLine = CommandLine(RootCommand(out))
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine.execute(args*)

@Command(name = "nerd-fonts-installer", mixinStandardHelpOptions = true)
final private[cli] class RootCommand(out: PrintWriter) extends java.util.concurrent.Callable[Integer]:
  private var showVersion: Boolean = false

  @CliOption(names = Array("--version"), description = Array("Print version information and exit."))
  def setShowVersion(value: Boolean): Unit = showVersion = value

  override def call(): Integer =
    if showVersion then out.println(s"nerd-fonts-installer ${BuildInfo.version}")
    else out.println(s"nerd-fonts-installer ${BuildInfo.version} (scala ${BuildInfo.scalaVersion})")
    Integer.valueOf(0)

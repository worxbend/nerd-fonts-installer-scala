package io.worxbend.nerdfonts.cli

/**
 * Whether the process is talking to a person: the default for colour.
 *
 * Go checks `ModeCharDevice` on both stdin and stdout. On the JDK 25 toolchain the default console provider
 * returns a `Console` only under the same condition, and `isTerminal` confirms it, so one JDK call answers the
 * same question without a subprocess and without native code.
 */
object TerminalProbe:
  def isTerminal(): Boolean = Option(System.console()).exists(_.isTerminal)

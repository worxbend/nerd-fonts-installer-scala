package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.environment.Environment

/**
 * Decides once per process whether renderers may emit ANSI colour.
 *
 * `NO_COLOR` (any value) and `TERM=dumb` always win, as the ecosystem conventions require. Otherwise colour is
 * on when a console is attached, or when the user forces it with `CLICOLOR_FORCE` / `FORCE_COLOR`: the console
 * check needs both stdin and stdout to be terminals, so a real output terminal with redirected stdin would
 * otherwise lose colour with no way back.
 */
object OutputStyle:
  private val noColour     = "NO_COLOR"
  private val term         = "TERM"
  private val dumbTerminal = "dumb"
  private val cliColour    = "CLICOLOR_FORCE"
  private val forceColour  = "FORCE_COLOR"

  def detect(env: Environment, consoleAttached: Boolean): ColourMode =
    val disabled = env.variable(noColour).isDefined || env.variable(term).contains(dumbTerminal)
    if !disabled && (consoleAttached || forced(env)) then ColourMode.Ansi else ColourMode.Plain

  private def forced(env: Environment): Boolean = env.variable(cliColour).exists(_ != "0") ||
    env.variable(forceColour).exists(value => value.nonEmpty && value != "0")

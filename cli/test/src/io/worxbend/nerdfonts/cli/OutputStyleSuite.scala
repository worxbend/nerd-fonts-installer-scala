package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.environment.Environment

/** The colour detection rules, one per test. */
final class OutputStyleSuite extends munit.FunSuite:
  private def detect(variables: Map[String, String], consoleAttached: Boolean): ColourMode =
    OutputStyle.detect(Environment.fixed(variables), consoleAttached)

  test("an attached console enables colour"):
    assertEquals(detect(Map.empty, consoleAttached = true), ColourMode.Ansi)

  test("no console means plain output"):
    assertEquals(detect(Map.empty, consoleAttached = false), ColourMode.Plain)

  test("NO_COLOR wins over a console, whatever its value"):
    assertEquals(detect(Map("NO_COLOR" -> ""), consoleAttached = true), ColourMode.Plain)

  test("NO_COLOR wins over a forced colour request"):
    assertEquals(
      detect(Map("NO_COLOR" -> "1", "FORCE_COLOR" -> "1"), consoleAttached = true),
      ColourMode.Plain,
    )

  test("a dumb terminal is plain"):
    assertEquals(detect(Map("TERM" -> "dumb"), consoleAttached = true), ColourMode.Plain)

  test("CLICOLOR_FORCE enables colour without a console"):
    assertEquals(detect(Map("CLICOLOR_FORCE" -> "1"), consoleAttached = false), ColourMode.Ansi)

  test("CLICOLOR_FORCE=0 does not force colour"):
    assertEquals(detect(Map("CLICOLOR_FORCE" -> "0"), consoleAttached = false), ColourMode.Plain)

  test("FORCE_COLOR enables colour without a console"):
    assertEquals(detect(Map("FORCE_COLOR" -> "true"), consoleAttached = false), ColourMode.Ansi)

  test("an empty or zero FORCE_COLOR does not force colour"):
    assertEquals(detect(Map("FORCE_COLOR" -> ""), consoleAttached = false), ColourMode.Plain)
    assertEquals(detect(Map("FORCE_COLOR" -> "0"), consoleAttached = false), ColourMode.Plain)

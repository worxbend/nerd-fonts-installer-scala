package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.environment.Environment

import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The colour detection rules, one per test. */
object OutputStyleSuite extends ZIOSpecDefault:
  private def detect(variables: Map[String, String], consoleAttached: Boolean) =
    OutputStyle.detect(Environment.fixed(variables), consoleAttached)

  override def spec: Spec[TestEnvironment, Any] = suite("OutputStyle")(
    test("an attached console enables colour"):
      detect(Map.empty, consoleAttached = true).map(mode => assertTrue(mode == ColourMode.Ansi))
    ,
    test("no console means plain output"):
      detect(Map.empty, consoleAttached = false).map(mode => assertTrue(mode == ColourMode.Plain))
    ,
    test("NO_COLOR wins over a console, whatever its value"):
      detect(Map("NO_COLOR" -> ""), consoleAttached = true).map(mode => assertTrue(mode == ColourMode.Plain))
    ,
    test("NO_COLOR wins over a forced colour request"):
      detect(Map("NO_COLOR" -> "1", "FORCE_COLOR" -> "1"), consoleAttached = true)
        .map(mode => assertTrue(mode == ColourMode.Plain))
    ,
    test("a dumb terminal is plain"):
      detect(Map("TERM" -> "dumb"), consoleAttached = true).map(mode => assertTrue(mode == ColourMode.Plain))
    ,
    test("CLICOLOR_FORCE enables colour without a console"):
      detect(Map("CLICOLOR_FORCE" -> "1"), consoleAttached = false)
        .map(mode => assertTrue(mode == ColourMode.Ansi))
    ,
    test("CLICOLOR_FORCE=0 does not force colour"):
      detect(Map("CLICOLOR_FORCE" -> "0"), consoleAttached = false)
        .map(mode => assertTrue(mode == ColourMode.Plain))
    ,
    test("FORCE_COLOR enables colour without a console"):
      detect(Map("FORCE_COLOR" -> "true"), consoleAttached = false)
        .map(mode => assertTrue(mode == ColourMode.Ansi))
    ,
    test("an empty FORCE_COLOR does not force colour"):
      detect(Map("FORCE_COLOR" -> ""), consoleAttached = false)
        .map(mode => assertTrue(mode == ColourMode.Plain))
    ,
    test("a zero FORCE_COLOR does not force colour"):
      detect(Map("FORCE_COLOR" -> "0"), consoleAttached = false)
        .map(mode => assertTrue(mode == ColourMode.Plain)),
  )

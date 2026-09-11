package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.picker.Fixtures.*
import io.worxbend.nerdfonts.picker.PickerKey.Char
import io.worxbend.nerdfonts.picker.PickerKey.Down
import io.worxbend.nerdfonts.picker.PickerKey.Enter
import io.worxbend.nerdfonts.picker.PickerKey.Space

import ox.discard

/** End-to-end sessions through the loop with a scripted terminal. */
final class PickerSessionSuite extends munit.FunSuite:

  test("choosing a release, toggling two families and pressing enter selects them"):
    val terminal = ScriptedTerminal(Vector(Enter, Space, Down, Space, Enter))
    val result   = PickerSession.run(releases, IconMode.Unicode, ColourMode.Plain, terminal)
    result match
      case Right(PickerOutcome.Selected(config)) =>
        assertEquals(config.families.map(_.value), Vector("Hack", "JetBrainsMono"))
        assertEquals(config.destination, DestinationPath.default)
        assertEquals(config.refreshFontCache, RefreshFontCache.Enabled)
        assertEquals(config.selector.render, "v3.4.0")
      case other                                 => fail(s"expected a selection, got $other")

  test("every key press is preceded by a full frame"):
    val terminal = ScriptedTerminal(Vector(Enter, Space, Enter))
    PickerSession.run(releases, IconMode.Unicode, ColourMode.Plain, terminal).discard
    assertEquals(terminal.frames.size, 3)
    assert(terminal.frames.head.lines.mkString("\n").contains("Select Nerd Fonts release"))
    assert(terminal.frames(1).lines.mkString("\n").contains("Select font families"))

  test("frames are laid out for the terminal's reported size"):
    val terminal = ScriptedTerminal(Vector(Char('q')), viewport = Viewport(60, 20))
    PickerSession.run(releases, IconMode.Unicode, ColourMode.Plain, terminal).discard
    assertEquals(terminal.frames.head.lines.size, 24)

  test("q cancels the session"):
    val terminal = ScriptedTerminal(Vector(Down, Char('q')))
    assertEquals(
      PickerSession.run(releases, IconMode.Auto, ColourMode.Ansi, terminal),
      Right(PickerOutcome.Cancelled),
    )

  test("end of input cancels the session"):
    val terminal = ScriptedTerminal(Vector(Enter))
    assertEquals(
      PickerSession.run(releases, IconMode.Auto, ColourMode.Plain, terminal),
      Right(PickerOutcome.Cancelled),
    )

  test("finishing with nothing selected is a cancellation"):
    val terminal = ScriptedTerminal(Vector(Enter, Space, Space, Enter))
    assertEquals(
      PickerSession.run(releases, IconMode.Auto, ColourMode.Plain, terminal),
      Right(PickerOutcome.Cancelled),
    )

  test("an unsafe stem in the selection is rejected"):
    val hostile  = Vector(latest.copy(families = Vector("../x")))
    val terminal = ScriptedTerminal(Vector(Enter, Space, Enter))
    val result   = PickerSession.run(hostile, IconMode.Auto, ColourMode.Plain, terminal)
    assert(result.exists(_.isInstanceOf[PickerOutcome.Rejected]), result.toString)

  test("raw mode is entered once and left once"):
    val terminal = ScriptedTerminal(Vector(Char('q')))
    PickerSession.run(releases, IconMode.Auto, ColourMode.Plain, terminal).discard
    assertEquals((terminal.rawModeEntries, terminal.rawModeExits), (1, 1))

  test("no releases is an error before the terminal is touched"):
    val terminal = ScriptedTerminal(Vector(Char('q')))
    assertEquals(
      PickerSession.run(Vector.empty, IconMode.Auto, ColourMode.Plain, terminal),
      Left(PickerError.NoReleases),
    )
    assertEquals(terminal.rawModeEntries, 0)

  test("a terminal that cannot enter raw mode is a picker error"):
    val failure  = TerminalError.RawModeUnavailable("stty -g: exit status 1")
    val terminal = ScriptedTerminal(Vector.empty, rawMode = Left(failure))
    assertEquals(
      PickerSession.run(releases, IconMode.Auto, ColourMode.Plain, terminal),
      Left(PickerError.Terminal(failure)),
    )

  test("picker errors render"):
    assertEquals(PickerError.NoReleases.render, "no Nerd Fonts releases available")
    assertEquals(
      PickerError.Terminal(TerminalError.RawModeUnavailable("boom")).render,
      "enter raw terminal mode: boom",
    )

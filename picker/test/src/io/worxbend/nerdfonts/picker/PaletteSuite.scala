package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode

/** Colour arithmetic, the text helpers in both colour modes, and the cell-width rules they rely on. */
final class PaletteSuite extends munit.FunSuite:

  test("hex colours parse and malformed ones degrade to white"):
    assertEquals(Colour.hex("#FF5FAF"), Colour(255, 95, 175))
    assertEquals(Colour.hex("nope"), Colour(255, 255, 255))
    assertEquals(Colour(255, 95, 175).hex, "#FF5FAF")

  test("interpolation rounds like Go in both directions"):
    assertEquals(Colour(0, 0, 0).towards(Colour(255, 255, 255), 0.5), Colour(128, 128, 128))
    assertEquals(Colour(255, 255, 255).towards(Colour(0, 0, 0), 0.5), Colour(128, 128, 128))

  test("the ramp returns its end stops at the boundaries"):
    assertEquals(Palette.rampColour(Palette.brandRamp, 0), Palette.pink)
    assertEquals(Palette.rampColour(Palette.brandRamp, 1), Palette.mint)
    assertEquals(Palette.rampColour(Palette.brandRamp, 0.5), Palette.violet)

  test("gradient text is the bare text in plain mode"):
    assertEquals(Palette.gradientText("nerd", Palette.brandRamp, ColourMode.Plain), "nerd")

  test("gradient text colours every glyph but keeps them intact in colour mode"):
    val painted = Palette.gradientText("nerd", Palette.brandRamp, ColourMode.Ansi)
    assertEquals(TextWidth.stripAnsi(painted), "nerd")
    assertEquals("\u001b\\[38;2;".r.findAllIn(painted).size, 4)

  test("a gradient rule is width cells wide and empty below one"):
    assertEquals(Palette.gradientRule(5, ColourMode.Plain), "─────")
    assertEquals(Palette.gradientRule(0, ColourMode.Ansi), "")

  test("spread pushes the right part to the edge or joins with one space"):
    assertEquals(Palette.spread(10, "ab", "cd"), "ab      cd")
    assertEquals(Palette.spread(3, "ab", "cd"), "ab cd")

  test("a stat line pads the label to twelve cells"):
    assertEquals(Palette.statLine("◆", "Release", "v3.4.0", ColourMode.Plain), "◆  Release       v3.4.0")

  test("percentage truncates and survives an empty total"):
    assertEquals(Palette.percentage(1, 3), 33)
    assertEquals(Palette.percentage(0, 0), 0)

  test("the progress bar fills proportionally over 24 cells"):
    assertEquals(Palette.progressBar(1, 3, ColourMode.Plain), "████████░░░░░░░░░░░░░░░░   33%")
    assertEquals(Palette.progressBar(0, 0, ColourMode.Plain), "░" * 24 + "    0%")

  test("paint is identity in plain mode and styled in colour mode"):
    assertEquals(Palette.paint("x", Styles.accent, ColourMode.Plain), "x")
    assert(Palette.paint("x", Styles.accent, ColourMode.Ansi).startsWith("\u001b["))

  test("display width ignores ANSI and counts wide glyphs as two cells"):
    assertEquals(TextWidth.displayWidth("\u001b[1mab\u001b[0m"), 2)
    assertEquals(TextWidth.displayWidth("✅"), 2)
    assertEquals(TextWidth.displayWidth("🚀x"), 3)
    assertEquals(TextWidth.displayWidth("✦"), 1)
    assertEquals(TextWidth.displayWidth("󰄲"), 1)

  test("truncation ends with an ellipsis and keeps escape sequences whole"):
    assertEquals(TextWidth.truncate("abcdef", 4), "abc…")
    assertEquals(TextWidth.truncate("abc", 4), "abc")
    assertEquals(TextWidth.truncate("\u001b[31mabcdef\u001b[0m", 4), "\u001b[31mabc\u001b[0m…")
    assertEquals(TextWidth.truncate("abc", 1), "…")

  test("fit pads to exactly the width"):
    assertEquals(TextWidth.fit("ab", 4), "ab  ")
    assertEquals(TextWidth.displayWidth(TextWidth.fit("✅✅✅", 4)), 4)

  test("wrap breaks on words and splits words longer than the width"):
    assertEquals(TextWidth.wrap("use filtering to jump", 10), Vector("use", "filtering", "to jump"))
    assertEquals(TextWidth.wrap("abcdefghij", 4), Vector("abcd", "efgh", "ij"))
    assertEquals(TextWidth.wrap("", 4), Vector(""))

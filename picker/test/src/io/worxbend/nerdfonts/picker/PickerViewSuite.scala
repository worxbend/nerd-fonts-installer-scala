package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.picker.Fixtures.*
import io.worxbend.nerdfonts.picker.PickerKey.*

/** The layout budget across the Go size matrix, plus the content each screen must carry. */
final class PickerViewSuite extends munit.FunSuite:
  private val families = (0 until 40).map(i => s"Family$i").toVector
  private val many     = Vector(latest.copy(families = families), previous.copy(families = families))

  private val matrix = Vector(
    Viewport(40, 10),
    Viewport(60, 20),
    Viewport(80, 24),
    Viewport(104, 25),
    Viewport(100, 30),
    Viewport(112, 34),
    Viewport(160, 50),
    Viewport(200, 60),
  )

  private def plain(model: PickerModel): Vector[String] = PickerView.render(model, ColourMode.Plain).lines
  private def text(model: PickerModel): String          = plain(model).mkString("\n")

  test("the frame never exceeds the safe height on either step across the size matrix"):
    for
      size <- matrix
      step <- Vector("release", "families")
    do
      val base   = model(many, IconMode.Unicode, size)
      val chosen = if step == "families" then press(base, Enter) else base
      val frame  = plain(chosen)
      val layout = chosen.layout
      assertEquals(frame.size, layout.safeHeight, s"$step at ${size.width}x${size.height}")

  test("no line is visibly wider than the safe width across the size matrix, in colour or plain"):
    for
      size    <- matrix
      step    <- Vector("release", "families")
      colours <- Vector(ColourMode.Plain, ColourMode.Ansi)
    do
      val base   = model(many, IconMode.Nerd, size)
      val chosen = if step == "families" then press(base, Enter) else base
      val frame  = PickerView.render(chosen, colours).lines
      frame.zipWithIndex.foreach: (line, index) =>
        assert(
          TextWidth.displayWidth(line) <= chosen.layout.safeWidth,
          s"$step at ${size.width}x${size.height} line $index is ${TextWidth.displayWidth(line)} wide: $line",
        )

  test("a plain frame contains no ANSI sequences"):
    val frame = text(press(model(), Enter, Space))
    assert(!frame.contains("\u001b["), frame)

  test("a colour frame carries ANSI colour and the same visible text as the plain frame"):
    val chosen = press(model(), Enter)
    val ansi   = PickerView.render(chosen, ColourMode.Ansi).lines
    assert(ansi.exists(_.contains("\u001b[38;2;")), ansi.mkString("\n"))
    assertEquals(ansi.map(TextWidth.stripAnsi), plain(chosen))

  test("the release step shows the title, releases and the help line"):
    val frame = text(model())
    assert(frame.contains("Select Nerd Fonts release"), frame)
    assert(frame.contains("◆ v3.4.0"), frame)
    assert(frame.contains("3 font archives"), frame)
    assert(frame.contains("enter: choose release  •  /: filter  •  esc/q: quit"), frame)

  test("the families step shows checkboxes, hints and the selection count"):
    val frame = text(press(model(), Enter, Space))
    assert(frame.contains("☑  Aa  Hack"), frame)
    assert(frame.contains("☐  Aa  JetBrainsMono"), frame)
    assert(frame.contains("monospace favorite"), frame)
    assert(frame.contains("1/3 selected for v3.4.0"), frame)

  test("nerd icons draw the nerd checkboxes and family glyphs"):
    val frame = text(press(model(icons = IconMode.Nerd), Enter, Space))
    assert(frame.contains("󰄲  󰌌  Hack"), frame)
    assert(frame.contains("󰄱  \ue70c  JetBrainsMono"), frame)

  test("unicode icons never require a patched font"):
    val frame = text(press(model(), Enter))
    assert(!frame.contains("󰌌"), frame)

  test("ascii icons use the bracket checkboxes and the plain logo"):
    val frame = text(press(model(icons = IconMode.Ascii), Enter))
    assert(frame.contains("[ ]  Aa  Hack"), frame)
    assert(frame.contains("[NF]  nerd-fonts-installer"), frame)

  test("a wide terminal shows the side panel and the breadcrumb"):
    val frame = text(model(viewport = Viewport(112, 34)))
    assert(frame.contains("Release cockpit"), frame)
    assert(frame.contains("◉ release › ○ families › ○ install"), frame)

  test("a narrow terminal drops the side panel and the breadcrumb"):
    val frame = text(model(viewport = Viewport(80, 30)))
    assert(!frame.contains("Release cockpit"), frame)
    assert(!frame.contains("› families"), frame)

  test("the families side panel shows the install plan and progress"):
    val frame = text(press(model(viewport = Viewport(120, 40)), Enter, Space))
    assert(frame.contains("Install plan"), frame)
    assert(frame.contains("1 of 3"), frame)
    assert(frame.contains("33%"), frame)
    assert(frame.contains("✓ release › ◉ families › ○ install"), frame)

  test("a short terminal uses the compact banner without subtitle and badges"):
    val frame = text(model(viewport = Viewport(80, 24)))
    assert(!frame.contains("patched glyphs"), frame)
    assert(!frame.contains("Pick the Nerd Fonts tag"), frame)

  test("a tall terminal shows the subtitle and badges"):
    val frame = text(model(viewport = Viewport(100, 30)))
    assert(frame.contains("patched glyphs"), frame)
    assert(frame.contains("Pick the Nerd Fonts tag to browse, then filter or confirm."), frame)

  test("the filter input replaces the list title and the status reports matches"):
    val frame = text(press(model(), Enter, Char('/'), Char('J')))
    assert(frame.contains("Filter: J█"), frame)
    assert(frame.contains("1 font • 2 filtered"), frame)

  test("an applied filter shows in the status bar"):
    val frame = text(press(model(), Enter, Char('/'), Char('J'), Up))
    assert(frame.contains("“J” 1 font • 2 filtered"), frame)

  test("a filter matching nothing says so"):
    assert(text(press(model(), Enter, Char('/'), Char('z'))).contains("Nothing matched"))

  test("the done screen summarises the selection"):
    val frame = text(press(model(), Enter, Space, Enter))
    assert(frame.contains("Selection locked in"), frame)
    assert(frame.contains("Ready to install"), frame)
    assert(frame.contains("Launching the installer…"), frame)

  test("a cancelled model renders an empty frame"):
    assertEquals(plain(press(model(), Char('q'))), Vector.empty[String])

  test("the item region pages long lists and shows a page indicator"):
    val frame = text(press(model(many, viewport = Viewport(96, 32)), Enter))
    assert(frame.contains("Family0"), frame)
    assert(!frame.contains("Family39"), frame)
    assert(frame.contains("1/14") || frame.contains("●"), frame)

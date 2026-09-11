package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode

/** A true-colour RGB value; `towards` reproduces Go `lerpHex` rounding so gradients match the reference. */
final private[picker] case class Colour(red: Int, green: Int, blue: Int):
  def hex: String = f"#$red%02X$green%02X$blue%02X"

  def foreground: fansi.Attr = fansi.Color.True(red, green, blue)
  def background: fansi.Attr = fansi.Back.True(red, green, blue)

  /** Linear interpolation, `t` in [0, 1], with Go's `int(float64(y-x)*t + 0.5)` truncation. */
  def towards(other: Colour, t: Double): Colour =
    def lerp(x: Int, y: Int): Int = x + ((y - x).toDouble * t + 0.5).toInt
    Colour(lerp(red, other.red), lerp(green, other.green), lerp(blue, other.blue))

private[picker] object Colour:
  /** `#RRGGBB`; anything else is white, as Go `hexRGB` degrades. */
  def hex(value: String): Colour =
    val digits = value.stripPrefix("#")
    if digits.length != 6 then Colour(255, 255, 255)
    else
      def channel(offset: Int): Int = Integer.parseInt(digits.substring(offset, offset + 2), 16)
      Colour(channel(0), channel(2), channel(4))

/**
 * The neon palette and the text helpers built on it. Every helper takes the `ColourMode` and returns the
 * bare text in `Plain` mode, so a single decision made by the CLI switches the whole picker to monochrome
 * without any renderer checking a flag of its own.
 */
private[picker] object Palette:
  val pink: Colour    = Colour.hex("#FF5FAF")
  val magenta: Colour = Colour.hex("#C75CFF")
  val violet: Colour  = Colour.hex("#8A7CFF")
  val blue: Colour    = Colour.hex("#5BA8FF")
  val cyan: Colour    = Colour.hex("#46E5E0")
  val mint: Colour    = Colour.hex("#5BF0B8")
  val text: Colour    = Colour.hex("#EDEDF7")
  val muted: Colour   = Colour.hex("#A2A2BE")
  val faint: Colour   = Colour.hex("#595972")
  val amber: Colour   = Colour.hex("#FFC857")
  val green: Colour   = Colour.hex("#54E08A")
  val red: Colour     = Colour.hex("#FF5C7A")
  val ink: Colour     = Colour.hex("#13131F")
  val panelHi: Colour = Colour.hex("#2E2A57")
  val descHi: Colour  = Colour.hex("#CFCBF2")

  /** The left-to-right gradient of the wordmark, rules and progress fill. */
  val brandRamp: Vector[Colour] = Vector(pink, magenta, violet, cyan, mint)

  /** How wide the progress bar is, in cells. */
  val progressCells: Int = 24

  /** Styled text in `Ansi` mode, the text itself in `Plain`. Never feed it already styled text. */
  def paint(value: String, style: fansi.Attrs, colours: ColourMode): String = colours match
    case ColourMode.Ansi  => style(fansi.Str(value)).render
    case ColourMode.Plain => value

  /** The colour at position `t` in [0, 1] along the stops. */
  def rampColour(stops: Vector[Colour], t: Double): Colour =
    if t <= 0 then stops.head
    else if t >= 1 then stops.last
    else
      val segment = t * (stops.size - 1)
      val index   = segment.toInt
      stops(index).towards(stops(index + 1), segment - index)

  /** Paints each code point with its own colour so even a short word shows the whole sweep. */
  def gradientText(value: String, stops: Vector[Colour], colours: ColourMode): String =
    val glyphs = value.codePoints().toArray.toVector.map(Character.toString)
    if glyphs.isEmpty || stops.isEmpty || colours == ColourMode.Plain then value
    else if stops.size == 1 || glyphs.size == 1 then paint(value, stops.head.foreground, colours)
    else
      glyphs.zipWithIndex
        .map((glyph, index) =>
          paint(glyph, rampColour(stops, index.toDouble / (glyphs.size - 1)).foreground, colours),
        )
        .mkString

  def gradientRule(width: Int, colours: ColourMode): String =
    if width < 1 then "" else gradientText("─" * width, brandRamp, colours)

  /** `left` at the start and `right` at the far edge of a `width`-cell line; one space apart when it will not fit. */
  def spread(width: Int, left: String, right: String): String =
    val gap = width - TextWidth.displayWidth(left) - TextWidth.displayWidth(right)
    if gap < 1 then s"$left $right" else left + " " * gap + right

  /** `icon  label(12 cells)  value`, the row shape of every side-panel stat. */
  def statLine(icon: String, label: String, value: String, colours: ColourMode): String =
    val paddedLabel = TextWidth.padRight(label, Palette.statLabelWidth)
    s"${paint(icon, Styles.accent, colours)}  ${paint(paddedLabel, Styles.label, colours)}  ${paint(value, Styles.value, colours)}"

  def progressBar(selected: Int, total: Int, colours: ColourMode): String =
    val filled = if total > 0 then math.min(progressCells, selected * progressCells / total) else 0
    val bar    = gradientText("█" * filled, brandRamp, colours) +
      paint("░" * (progressCells - filled), Styles.progressTrack, colours)
    s"$bar  ${paint(f"${percentage(selected, total)}%3d%%", Styles.accent, colours)}"

  /** Integer percentage with Go's truncating division; 0 when there is nothing to select. */
  def percentage(selected: Int, total: Int): Int = if total == 0 then 0 else selected * 100 / total

  private val statLabelWidth: Int = 12

/** The lipgloss styles of the Go picker, as fansi attribute sets. Italic has no fansi equivalent and is dropped. */
private[picker] object Styles:
  import Palette.*

  val title: fansi.Attrs            = fansi.Bold.On ++ text.foreground
  val subtitle: fansi.Attrs         = muted.foreground
  val help: fansi.Attrs             = faint.foreground
  val key: fansi.Attrs              = fansi.Bold.On ++ cyan.foreground
  val error: fansi.Attrs            = fansi.Bold.On ++ red.foreground
  val success: fansi.Attrs          = fansi.Bold.On ++ green.foreground
  val accent: fansi.Attrs           = fansi.Bold.On ++ cyan.foreground
  val label: fansi.Attrs            = fansi.Bold.On ++ muted.foreground
  val value: fansi.Attrs            = text.foreground
  val progressTrack: fansi.Attrs    = faint.foreground
  val spinner: fansi.Attrs          = cyan.foreground
  val badgePkg: fansi.Attrs         = fansi.Bold.On ++ ink.foreground ++ pink.background
  val badgeFont: fansi.Attrs        = fansi.Bold.On ++ ink.foreground ++ cyan.background
  val badgeLaunch: fansi.Attrs      = fansi.Bold.On ++ ink.foreground ++ mint.background
  val crumbActive: fansi.Attrs      = fansi.Bold.On ++ cyan.foreground
  val crumbDone: fansi.Attrs        = green.foreground
  val crumbTodo: fansi.Attrs        = faint.foreground
  val crumbSeparator: fansi.Attrs   = faint.foreground
  val listTitle: fansi.Attrs        = fansi.Bold.On ++ ink.foreground ++ cyan.background
  val listRule: fansi.Attrs         = faint.foreground
  val statusBar: fansi.Attrs        = muted.foreground
  val activeFilter: fansi.Attrs     = fansi.Bold.On ++ amber.foreground
  val filterCount: fansi.Attrs      = pink.foreground
  val filterPrompt: fansi.Attrs     = fansi.Bold.On ++ cyan.foreground
  val pagination: fansi.Attrs       = faint.foreground
  val paginationActive: fansi.Attrs = text.foreground
  val selectedMarker: fansi.Attrs   = pink.foreground
  val selectedTitle: fansi.Attrs    = fansi.Bold.On ++ text.foreground ++ panelHi.background
  val selectedDesc: fansi.Attrs     = descHi.foreground ++ panelHi.background
  val normalTitle: fansi.Attrs      = fansi.Bold.On ++ text.foreground
  val normalDesc: fansi.Attrs       = muted.foreground
  val filterMatch: fansi.Attrs      = fansi.Bold.On ++ fansi.Underlined.On ++ amber.foreground

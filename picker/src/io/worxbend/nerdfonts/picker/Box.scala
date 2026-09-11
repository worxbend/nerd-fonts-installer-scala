package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode

/**
 * A rounded, padded box of an exact size: lipgloss' `Border(RoundedBorder()).Padding(1, 2)` without the
 * reflow. Content is truncated rather than wrapped because every extra row would break the height budget
 * that keeps the frame inside the terminal; callers wrap deliberately where Go's layout relies on it.
 */
private[picker] object Box:
  /** Cells the border and padding take from the total width. */
  val horizontalChrome: Int = 6

  /** Rows the border and padding add to the content. */
  val verticalChrome: Int = 4

  /** Exactly `width` columns wide and `lines.size + 4` rows tall. */
  def render(width: Int, lines: Vector[String], border: Colour, colours: ColourMode): Vector[String] =
    val inner                      = math.max(0, width - 2)
    val contentWidth               = math.max(0, width - horizontalChrome)
    def edge(text: String): String = Palette.paint(text, border.foreground, colours)
    val side                       = edge("│")
    val blank                      = side + " " * inner + side
    val top                        = edge("╭" + "─" * inner + "╮")
    val bottom                     = edge("╰" + "─" * inner + "╯")
    val content                    = lines.map(line => s"$side  ${TextWidth.fit(line, contentWidth)}  $side")
    (top +: blank +: content) ++ Vector(blank, bottom)

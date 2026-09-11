package io.worxbend.nerdfonts.picker

import scala.annotation.tailrec

import ox.pipe

/**
 * Terminal cell arithmetic on possibly styled text.
 *
 * Display width strips SGR sequences and counts one cell per code point, two for the East Asian wide and
 * fullwidth ranges and the wide emoji blocks (the `wcwidth` table's wide set, in the ranges terminals agree
 * on). Combining marks and zero-width joiners are counted as one because the picker never renders them; this
 * is a layout budget, not a Unicode implementation, and erring towards "wider" keeps frames inside the
 * terminal.
 */
private[picker] object TextWidth:
  private val escape: Char  = '\u001b'
  private val reset: String = "\u001b[0m"

  def stripAnsi(text: String): String = text.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "")

  def displayWidth(text: String): Int = stripAnsi(text).codePoints().map(codePointWidth).sum()

  def codePointWidth(codePoint: Int): Int = if isWide(codePoint) then 2 else 1

  def padRight(text: String, width: Int): String = text + " " * math.max(0, width - displayWidth(text))

  /** Truncate to `width` cells, keeping escape sequences intact and ending with `…` when something was cut. */
  def truncate(text: String, width: Int): String =
    if displayWidth(text) <= width then text
    else if width <= 0 then ""
    else if width == 1 then "…"
    else take(text, width - 1, 0, java.lang.StringBuilder(), sawEscape = false) + "…"

  /** Truncate then pad, so the result is exactly `width` cells. */
  def fit(text: String, width: Int): String = padRight(truncate(text, width), width)

  /** Greedy word wrap of plain text; a word longer than the width is split. */
  def wrap(text: String, width: Int): Vector[String] =
    val words = text.split(" ").toVector.filter(_.nonEmpty)
    words
      .foldLeft(Vector.empty[String]): (lines, word) =>
        lines.lastOption match
          case Some(line) if displayWidth(line) + 1 + displayWidth(word) <= width =>
            lines.init :+ s"$line $word"
          case _                                                                  => lines ++ splitLong(word, width)
      .pipe(lines => if lines.isEmpty then Vector("") else lines)

  private def splitLong(word: String, width: Int): Vector[String] =
    if displayWidth(word) <= width then Vector(word)
    else word.grouped(math.max(1, width)).toVector

  @tailrec
  private def take(
      text: String,
      budget: Int,
      index: Int,
      out: java.lang.StringBuilder,
      sawEscape: Boolean,
  ): String =
    if index >= text.length then out.toString
    else if text.charAt(index) == escape then
      val end = sequenceEnd(text, index)
      take(text, budget, end, out.append(text, index, end), sawEscape = true)
    else
      val codePoint = text.codePointAt(index)
      val cells     = codePointWidth(codePoint)
      if cells > budget then if sawEscape then out.append(reset).toString else out.toString
      else
        take(
          text,
          budget - cells,
          index + Character.charCount(codePoint),
          out.appendCodePoint(codePoint),
          sawEscape,
        )

  // The index just past a CSI sequence: ESC '[' parameter/intermediate bytes, then one final byte.
  private def sequenceEnd(text: String, start: Int): Int =
    @tailrec
    def scan(index: Int): Int =
      if index >= text.length then index
      else if text.charAt(index) >= '@' && text.charAt(index) <= '~' then index + 1
      else scan(index + 1)
    if start + 1 < text.length && text.charAt(start + 1) == '[' then scan(start + 2) else start + 1

  private val wideRanges: Vector[(Int, Int)] = Vector(
    (0x1100, 0x115f),
    (0x231a, 0x231b),
    (0x2329, 0x232a),
    (0x23e9, 0x23ec),
    (0x23f0, 0x23f0),
    (0x23f3, 0x23f3),
    (0x25fd, 0x25fe),
    (0x2614, 0x2615),
    (0x2648, 0x2653),
    (0x267f, 0x267f),
    (0x2693, 0x2693),
    (0x26a1, 0x26a1),
    (0x26aa, 0x26ab),
    (0x26bd, 0x26be),
    (0x26c4, 0x26c5),
    (0x26ce, 0x26ce),
    (0x26d4, 0x26d4),
    (0x26ea, 0x26ea),
    (0x26f2, 0x26f3),
    (0x26f5, 0x26f5),
    (0x26fa, 0x26fa),
    (0x26fd, 0x26fd),
    (0x2705, 0x2705),
    (0x270a, 0x270b),
    (0x2728, 0x2728),
    (0x274c, 0x274c),
    (0x274e, 0x274e),
    (0x2753, 0x2755),
    (0x2757, 0x2757),
    (0x2795, 0x2797),
    (0x27b0, 0x27b0),
    (0x27bf, 0x27bf),
    (0x2b1b, 0x2b1c),
    (0x2b50, 0x2b50),
    (0x2b55, 0x2b55),
    (0x2e80, 0x303e),
    (0x3041, 0x33ff),
    (0x3400, 0x4dbf),
    (0x4e00, 0x9fff),
    (0xa000, 0xa4cf),
    (0xa960, 0xa97f),
    (0xac00, 0xd7a3),
    (0xf900, 0xfaff),
    (0xfe10, 0xfe19),
    (0xfe30, 0xfe6f),
    (0xff00, 0xff60),
    (0xffe0, 0xffe6),
    (0x1f004, 0x1f004),
    (0x1f0cf, 0x1f0cf),
    (0x1f18e, 0x1f18e),
    (0x1f191, 0x1f19a),
    (0x1f200, 0x1f251),
    (0x1f300, 0x1f64f),
    (0x1f680, 0x1f6ff),
    (0x1f900, 0x1f9ff),
    (0x1fa70, 0x1faff),
    (0x20000, 0x3fffd),
  )

  private def isWide(codePoint: Int): Boolean =
    wideRanges.exists((from, to) => codePoint >= from && codePoint <= to)

package io.worxbend.nerdfonts

/**
 * Neutralises terminal escape injection in upstream-controlled text — a release tag, a family stem from the
 * GitHub API, a zip entry name — before it reaches a real terminal.
 *
 * `FamilyName.parse` deliberately allows control characters (only `/`, `\`, NUL, `.`/`..` and an absolute path
 * are rejected, so a name can still round-trip through `%q`-style quoting in an error message), and a release
 * asset stem is untrusted GitHub API data. Without this, a spoofed release or a hostile zip entry can carry a
 * raw escape character (title-setting `OSC`, screen-clearing `CSI`, …) straight into a progress line.
 *
 * Every C0 control character (code points 0–31, most importantly the escape character at 27), `DEL` (127) and
 * the C1 range (128–159, reachable once UTF-8 is decoded to `Char`) is replaced one-for-one with `?`.
 * Substitution rather than removal keeps the string the same length for callers that track display positions.
 * Nothing else is touched: this is a terminal-safety transform, not a display-width or validation one.
 */
private[nerdfonts] object TerminalSafe:
  private val placeholder = '?'
  private val c0End       = 31 // NUL .. US, the escape character (27) among them
  private val delete      = 127
  private val c1Start     = 128
  private val c1End       = 159

  def sanitize(value: String): String = value.map(safeChar)

  private def safeChar(ch: Char): Char = if isControl(ch) then placeholder else ch

  private def isControl(ch: Char): Boolean =
    val code = ch.toInt
    code <= c0End || code == delete || (code >= c1Start && code <= c1End)

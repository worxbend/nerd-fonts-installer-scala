package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.releases.ReleaseError

import java.io.Writer

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import ox.forkDiscard
import ox.sleep
import ox.supervised

/**
 * Go's `tui.LoadReleases` on stderr: a brand line, then a spinner line rewritten in place while the catalogue
 * loads. No raw mode is needed because nothing is read; the line is redrawn with `\r` and padded with spaces
 * rather than `ESC[K` so `Plain` output stays free of control sequences. The ticker is a daemon fork of a
 * scope whose body is the load itself, so it is cancelled and joined before the final line is written and
 * can never scribble over it.
 */
object ReleaseLoadingSpinner:
  val frames: Vector[String]   = Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
  val interval: FiniteDuration = 83.millis
  val message: String          = "Loading Nerd Fonts releases"
  val loaded: String           = "✓ Releases loaded"
  val interrupted: String      = "interrupted"

  def around[A](stderr: Writer, colours: ColourMode)(
      load: () => Either[ReleaseError, A],
  ): Either[ReleaseError, A] =
    write(stderr, s"\n  ${Palette.gradientText("✦ nerd-fonts-installer", Palette.brandRamp, colours)}\n")
    write(stderr, spinnerLine(0, colours))
    // An `InterruptedException` unwinds `supervised` without ever producing `result` (the ticker fork is
    // still cancelled and joined first), so the line has to be ended here instead of after the match below;
    // otherwise the next stderr line would continue writing on top of wherever the ticker left the cursor.
    // The interrupt flag is already cleared once the exception is in flight, so this write is safe.
    val result =
      try
        supervised:
          forkDiscard(tick(stderr, colours))
          load()
      catch
        case interrupt: InterruptedException =>
          write(stderr, "\r" + overwriting(interruptedLine(colours), colours) + "\n")
          throw interrupt
    write(stderr, "\r" + overwriting(finalLine(result, colours), colours) + "\n")
    result

  // The final line is padded to the spinner line's width so no tail of the longer text survives the `\r`.
  private def overwriting(line: String, colours: ColourMode): String =
    TextWidth.padRight(line, TextWidth.displayWidth(spinnerLine(0, colours)))

  private def tick(stderr: Writer, colours: ColourMode): Unit = Iterator
    .from(1)
    .foreach: index =>
      sleep(interval)
      write(stderr, "\r" + spinnerLine(index, colours))

  private def spinnerLine(index: Int, colours: ColourMode): String =
    val glyph = Palette.paint(frames(index % frames.size), Styles.spinner, colours)
    s"  $glyph ${Palette.paint(message, Styles.accent, colours)}"

  private def finalLine[A](result: Either[ReleaseError, A], colours: ColourMode): String = result match
    case Right(_)    => s"  ${Palette.paint(loaded, Styles.success, colours)}"
    case Left(error) => s"  ${Palette.paint(error.render, Styles.error, colours)}"

  private def interruptedLine(colours: ColourMode): String =
    s"  ${Palette.paint(interrupted, Styles.error, colours)}"

  private def write(stderr: Writer, text: String): Unit =
    stderr.write(text)
    stderr.flush()

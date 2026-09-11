package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.picker.Fixtures.*
import io.worxbend.nerdfonts.releases.ReleaseError

import java.io.StringWriter

import scala.concurrent.duration.DurationInt

import ox.discard
import ox.sleep

/** The stderr spinner block around the release load. */
final class ReleaseLoadingSpinnerSuite extends munit.FunSuite:
  private val loadingLine = "  ⠋ Loading Nerd Fonts releases"

  test("a successful load prints the brand line, ticks the spinner and ends with the loaded line"):
    val out    = StringWriter()
    val result = ReleaseLoadingSpinner.around(out, ColourMode.Plain): () =>
      sleep(300.millis)
      Right(releases)
    assertEquals(result, Right(releases))
    val text   = out.toString
    assert(text.startsWith(s"\n  ✦ nerd-fonts-installer\n$loadingLine"), text)
    assert(text.contains("\r  ⠙ Loading Nerd Fonts releases"), text)
    assert(
      text.endsWith(
        "\r  ✓ Releases loaded" + " " * (loadingLine.length - "  ✓ Releases loaded".length) + "\n",
      ),
      text,
    )

  test("a failed load ends with the error message and returns it"):
    val out     = StringWriter()
    val result  = ReleaseLoadingSpinner.around(out, ColourMode.Plain)(() => Left(ReleaseError.NoReleases))
    assertEquals(result, Left(ReleaseError.NoReleases))
    val failure = "  no Nerd Fonts releases found"
    assert(
      out.toString.endsWith("\r" + failure + " " * (loadingLine.length - failure.length) + "\n"),
      out.toString,
    )

  test("plain output carries no ANSI sequences"):
    val out = StringWriter()
    ReleaseLoadingSpinner.around(out, ColourMode.Plain)(() => Right(())).discard
    assert(!out.toString.contains("\u001b["), out.toString)

  test("colour output paints the brand line"):
    val out = StringWriter()
    ReleaseLoadingSpinner.around(out, ColourMode.Ansi)(() => Right(())).discard
    assert(out.toString.contains("\u001b[38;2;"), out.toString)
    assertEquals(
      TextWidth.stripAnsi(out.toString).linesIterator.toVector.take(2),
      Vector("", "  ✦ nerd-fonts-installer"),
    )

  test("the ticker never writes after the final line"):
    val out    = StringWriter()
    ReleaseLoadingSpinner.around(out, ColourMode.Plain)(() => Right(())).discard
    val length = out.toString.length
    sleep(250.millis)
    assertEquals(out.toString.length, length)

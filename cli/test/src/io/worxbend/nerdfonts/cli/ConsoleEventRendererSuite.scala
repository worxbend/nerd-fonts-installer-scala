package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallEvent
import io.worxbend.nerdfonts.releases.ReleaseUrls

import java.io.PrintWriter
import java.io.StringWriter

/** The §6.9 table: all eight events, in both colour modes. */
final class ConsoleEventRendererSuite extends munit.FunSuite:
  private val esc    = "\u001b"
  private val hack   = family("Hack")
  private val url    = ReleaseUrls.github.download(ReleaseSelector.Latest, hack)
  private val root   = os.Path("/fonts")
  private val target = root / "Hack"

  private val events = Vector(
    InstallEvent.WouldInstall(hack, url, target),
    InstallEvent.WouldRefreshCache(root),
    InstallEvent.Started(hack, url),
    InstallEvent.Installed(hack, target),
    InstallEvent.ChecksumManifestUnavailable("404 Not Found"),
    InstallEvent.FontCacheUnavailable,
    InstallEvent.RefreshingFontCache(root),
    InstallEvent.FontCacheRefreshed,
  )

  private val plainOut = s"• Would install Hack from ${url.value} into /fonts/Hack\n" +
    "↻ Would refresh font cache for /fonts\n"

  private val plainErr = s"⠋ Installing Nerd Font Hack from ${url.value}\n" +
    "✅ Installed Hack into /fonts/Hack\n" +
    "• Checksum manifest unavailable (404 Not Found); installing without integrity verification.\n" +
    "• fc-cache is not available; skipping font cache refresh.\n" +
    "⠋ Refreshing font cache for /fonts\n" +
    "✅ Font cache refreshed\n"

  private def render(colours: ColourMode): (String, String) =
    val out      = StringWriter()
    val err      = StringWriter()
    val renderer = ConsoleEventRenderer(PrintWriter(out, true), PrintWriter(err, true), colours)
    events.foreach(renderer.emit)
    (out.toString, err.toString)

  private def stripAnsi(text: String): String = text.replaceAll("\u001b\\[[0-9;]*m", "")

  test("plain mode prints the Go wording with glyphs and routes plan lines to stdout"):
    assertEquals(render(ColourMode.Plain), (plainOut, plainErr))

  test("plain output contains no escape sequences"):
    val (out, err) = render(ColourMode.Plain)
    assert(!(out + err).contains(esc))

  test("ansi mode prints the same text once colours are stripped"):
    val (out, err) = render(ColourMode.Ansi)
    assertEquals((stripAnsi(out), stripAnsi(err)), (plainOut, plainErr))

  test("ansi mode paints each role with its lipgloss colour"):
    val (out, err) = render(ColourMode.Ansi)
    val text       = out + err
    assert(text.contains(s"$esc[38;5;63m•"), text)
    assert(text.contains(s"$esc[38;5;63m↻"), text)
    assert(text.contains(s"$esc[38;5;63m⠋"), text)
    assert(text.contains(s"$esc[38;5;42m$esc[1m✅"), text)
    assert(text.contains(s"$esc[38;5;214m•"), text)
    assert(text.contains(s"$esc[38;5;81m$esc[1mHack"), text)
    assert(text.contains(s"$esc[38;5;39m$esc[4mhttps://"), text)
    assert(text.contains(s"$esc[38;5;219m/fonts"), text)

  test("plan lines never reach stderr and progress lines never reach stdout"):
    val (out, err) = render(ColourMode.Ansi)
    assert(!stripAnsi(out).contains("Installing") && !stripAnsi(err).contains("Would install"))

package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.TerminalSafe
import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.install.InstallEvent
import io.worxbend.nerdfonts.install.InstallEventSink

import java.io.PrintWriter

/**
 * The only place install events become text: the message wording, the glyph prefixes, and the stdout/stderr
 * split (the dry-run plan is machine-readable and goes to stdout; progress and warnings go to stderr).
 *
 * The `match` is exhaustive without a wildcard so a new event cannot ship unrendered. There is no locking:
 * the `InstallEventSink` contract promises one caller at a time, and `FontInstaller` keeps that promise
 * through its actor. Glyphs are part of the wording and survive `Plain` mode; only colour is dropped.
 */
final class ConsoleEventRenderer(out: PrintWriter, err: PrintWriter, colours: ColourMode)
    extends InstallEventSink:
  import ConsoleEventRenderer.*

  def emit(event: InstallEvent): Unit = event match
    case InstallEvent.WouldInstall(family, url, target)  => out.println(
        s"${paint("•", spinner)} Would install ${paint(family.value, font)} from ${paint(url.value, link)} into ${paint(target.toString, path)}",
      )
    case InstallEvent.WouldRefreshCache(root)            =>
      out.println(s"${paint("↻", spinner)} Would refresh font cache for ${paint(root.toString, path)}")
    case InstallEvent.Started(family, url)               => err.println(
        s"${paint("⠋", spinner)} Installing Nerd Font ${paint(family.value, font)} from ${paint(url.value, link)}",
      )
    case InstallEvent.Installed(family, target)          => err.println(
        s"${paint("✅", success)} Installed ${paint(family.value, font)} into ${paint(target.toString, path)}",
      )
    case InstallEvent.ChecksumManifestUnavailable(cause) => err.println(
        s"${paint("•", warning)} Checksum manifest unavailable ($cause); installing without integrity verification.",
      )
    case InstallEvent.FontCacheUnavailable               =>
      err.println(s"${paint("•", warning)} fc-cache is not available; skipping font cache refresh.")
    case InstallEvent.RefreshingFontCache(root)          =>
      err.println(s"${paint("⠋", spinner)} Refreshing font cache for ${paint(root.toString, path)}")
    case InstallEvent.FontCacheRefreshed                 => err.println(s"${paint("✅", success)} Font cache refreshed")

  // `TerminalSafe` first: a family name (`FamilyName.parse` allows control characters) or a zip entry name
  // from a hostile release can carry a raw escape character, and `Plain` mode — `NO_COLOR`, a redirected
  // stderr, a dumb terminal — has no other defence between upstream data and a real terminal. `Sanitize`, not
  // the throwing default, on top of that in `Ansi` mode: the now-control-free value may still contain bytes
  // fansi's own parser would otherwise refuse, and a warning line must never turn into a crash.
  private def paint(value: String, style: fansi.Attrs): String =
    val safe = TerminalSafe.sanitize(value)
    colours match
      case ColourMode.Ansi  => style(fansi.Str(safe, fansi.ErrorMode.Sanitize)).render
      case ColourMode.Plain => safe

object ConsoleEventRenderer:
  /** The 256-colour numbers, one attribute set per role. */
  private[cli] val spinner: fansi.Attrs = fansi.Color.Full(63)
  private[cli] val success: fansi.Attrs = fansi.Bold.On ++ fansi.Color.Full(42)
  private[cli] val warning: fansi.Attrs = fansi.Color.Full(214)
  private[cli] val font: fansi.Attrs    = fansi.Bold.On ++ fansi.Color.Full(81)
  private[cli] val link: fansi.Attrs    = fansi.Underlined.On ++ fansi.Color.Full(39)
  private[cli] val path: fansi.Attrs    = fansi.Color.Full(219)

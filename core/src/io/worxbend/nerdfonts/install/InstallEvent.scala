package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.releases.DownloadUrl

/**
 * Everything the engine says to the user. The engine never writes to a stream and no `String` message
 * crosses the sink, so the CLI owns wording, colour and stdout/stderr routing in one place and the engine
 * stays testable against values. The enum is closed: a renderer matches exhaustively without a wildcard.
 */
enum InstallEvent:
  case WouldInstall(family: FamilyName, url: DownloadUrl, target: os.Path)
  case WouldRefreshCache(root: os.Path)
  case Started(family: FamilyName, url: DownloadUrl)
  case Installed(family: FamilyName, target: os.Path)
  case ChecksumManifestUnavailable(cause: String)
  case FontCacheUnavailable
  case RefreshingFontCache(root: os.Path)
  case FontCacheRefreshed

/**
 * Where the engine's progress goes.
 *
 * Contract: `emit` is invoked from one thread at a time, in submission order. `FontInstaller` serialises the
 * concurrent family workers through an Ox `Actor`, so an implementation is a plain writer and must not add
 * locking of its own.
 */
trait InstallEventSink:
  def emit(event: InstallEvent): Unit

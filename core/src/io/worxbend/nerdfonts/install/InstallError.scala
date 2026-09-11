package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.releases.DownloadUrl
import io.worxbend.nerdfonts.releases.Sha256Digest

import scala.concurrent.duration.FiniteDuration

/**
 * Why a run failed. Exactly one of these is ever reported: the fan-out stops at the first failing family,
 * so the CLI prints a single `install fonts: <render>` line, as the Go reference does.
 */
enum InstallError:
  case Destination(root: os.Path, cause: String)
  case Family(name: FamilyName, cause: FamilyInstallError)
  case FontCache(root: os.Path, cause: FontCacheError)

  /** The Go wording without the `install fonts: ` prefix, which the CLI adds. */
  def render: String = this match
    case Destination(root, cause) => s"create destination $root: $cause"
    case Family(name, cause)      => s"install Nerd Font family ${name.value}: ${cause.render(name)}"
    case FontCache(root, cause)   => s"run fc-cache for $root: ${cause.render}"

/**
 * Why one family could not be installed, one case per step of §6.3.
 *
 * `render` takes the family because the checksum wording names it and only the [[InstallError.Family]]
 * wrapper knows the name; carrying it in every case would duplicate a value the wrapper already holds.
 */
enum FamilyInstallError:
  case TempZip(cause: String)
  case Download(url: DownloadUrl, cause: HttpError)
  case Copy(url: DownloadUrl, zip: os.Path, cause: String)
  case ChecksumMismatch(actual: Sha256Digest, expected: Sha256Digest)
  case Staging(root: os.Path, cause: String)
  case Extraction(zip: os.Path, staging: os.Path, cause: ArchiveError)
  case Swap(cause: SwapError)
  case TimedOut(limit: FiniteDuration)

  def render(family: FamilyName): String = this match
    case TempZip(cause)                   => s"create temporary zip file: $cause"
    case Download(url, cause)             => s"download ${url.value}: ${cause.render}"
    case Copy(url, zip, cause)            => s"copy download ${url.value} to $zip: $cause"
    case ChecksumMismatch(actual, wanted) =>
      s"checksum mismatch for ${family.value}: downloaded sha256 ${actual.value}, expected ${wanted.value}"
    case Staging(root, cause)             => s"create temporary family destination in $root: $cause"
    case Extraction(zip, staging, cause)  => s"extract $zip to $staging: ${cause.render}"
    case Swap(cause)                      => cause.render
    case TimedOut(limit)                  => s"timed out after $limit"

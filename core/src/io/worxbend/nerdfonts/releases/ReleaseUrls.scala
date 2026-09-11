package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.Url

import java.nio.charset.StandardCharsets

/**
 * The URL of one family's zip archive. Distinct from a plain `Url` so that install events and errors can
 * only ever carry a URL that was built from a validated `FamilyName`.
 */
opaque type DownloadUrl = String

object DownloadUrl:
  private[releases] def apply(value: String): DownloadUrl = value

  extension (url: DownloadUrl)
    /** The request form. */
    def url: Url = Url(url)

    /** The text, for messages. */
    def value: String = url

/**
 * Builds the release asset URLs below one `releases` base. Both segments are escaped with Go's
 * `url.PathEscape` semantics so the tool requests byte-for-byte the same URLs as the reference for tags and
 * families containing spaces.
 *
 * The base is a value rather than a constant so the composition root can point a whole run at a local stub
 * (the CI interrupt smoke test); every production path uses [[ReleaseUrls.github]].
 */
final class ReleaseUrls(base: Url):
  def download(selector: ReleaseSelector, family: FamilyName): DownloadUrl =
    DownloadUrl(asset(selector, s"${PathEscape.escape(family.value)}.zip"))

  def checksums(selector: ReleaseSelector): Url = Url(asset(selector, ReleaseUrls.checksumFile))

  private def asset(selector: ReleaseSelector, file: String): String = selector match
    case ReleaseSelector.Latest      => s"${base.value}/latest/download/$file"
    case ReleaseSelector.Tagged(tag) => s"${base.value}/download/${PathEscape.escape(tag.value)}/$file"

object ReleaseUrls:
  private val checksumFile = "SHA-256.txt"

  /** The real Nerd Fonts release page; the only base a shipped run ever uses. */
  val github: ReleaseUrls = ReleaseUrls(Url("https://github.com/ryanoasis/nerd-fonts/releases"))

  /** A trailing slash on the base is tolerated so an operator-supplied override cannot double it. */
  def apply(base: Url): ReleaseUrls = new ReleaseUrls(Url(base.value.stripSuffix("/")))

/**
 * Go's `url.PathEscape`: percent-encodes everything in a path segment except RFC 3986 unreserved characters
 * and the sub-delimiters Go leaves alone (`$&+:=@`). Java's `URLEncoder` encodes a space as `+` and leaves
 * `*` alone, so it cannot be used here.
 */
private[releases] object PathEscape:
  private val hex = "0123456789ABCDEF"

  def escape(segment: String): String = segment.getBytes(StandardCharsets.UTF_8).flatMap(encode).mkString

  private def encode(byte: Byte): String =
    val c = (byte & 0xff).toChar
    if isUnreserved(c) || isKeptDelimiter(c) then c.toString
    else s"%${hex((byte >> 4) & 0xf)}${hex(byte & 0xf)}"

  private def isUnreserved(c: Char): Boolean =
    c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' ||
      c == '-' || c == '_' || c == '.' || c == '~'

  private def isKeptDelimiter(c: Char): Boolean =
    c == '$' || c == '&' || c == '+' || c == ':' || c == '=' || c == '@'

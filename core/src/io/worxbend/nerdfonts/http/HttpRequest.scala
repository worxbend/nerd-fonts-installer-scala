package io.worxbend.nerdfonts.http

/** A GET request: the URL plus any headers beyond the adapter's defaults. */
final case class HttpRequest(url: Url, headers: Map[String, String] = Map.empty)

/**
 * What happens when a body runs past its `ByteLimit`.
 *
 * `Reject` is the safe default and turns an oversized body into `HttpError.TooLarge` even after `consume`
 * returned. `Truncate` exists for exactly one caller, the checksum manifest, where Go reads through
 * `io.LimitReader` and silently keeps the first megabyte.
 */
enum Overflow:
  case Reject, Truncate

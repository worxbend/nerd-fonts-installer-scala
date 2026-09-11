package io.worxbend.nerdfonts.http

/**
 * What happens when a body runs past its `ByteLimit`.
 *
 * `Reject` is the safe default and turns an oversized body into `HttpError.TooLarge` even after `consume`
 * returned. `Truncate` exists for exactly one caller, the checksum manifest, where Go reads through
 * `io.LimitReader` and silently keeps the first megabyte.
 *
 * Constructed at every `HttpClient.get` call site (`ArchiveExtractor`, `FontInstaller`, `HttpClient`,
 * `ResponseDelivery`), so it gets its own file rather than riding along with the unrelated `HttpRequest`.
 */
enum Overflow:
  case Reject, Truncate

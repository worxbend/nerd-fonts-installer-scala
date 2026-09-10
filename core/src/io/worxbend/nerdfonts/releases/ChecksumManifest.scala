package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.FamilyName

/**
 * Parses the `SHA-256.txt` a release publishes: one `<hex>  <file>` line per asset.
 *
 * Only `.zip` entries matter, and only those whose stem is a valid `FamilyName`: a stem that fails to parse
 * could never match a validated family, so skipping it loses nothing. Malformed lines, including a last
 * line cut by the 1 MiB read limit, are skipped the same way. A later line for the same family wins, as
 * with Go's map assignment.
 */
object ChecksumManifest:
  private val zipSuffix = ".zip"

  def parse(text: String): Map[FamilyName, Sha256Digest] = text.linesIterator.flatMap(entry).toMap

  private def entry(line: String): Option[(FamilyName, Sha256Digest)] = line.trim.split("\\s+").toList match
    case digest :: file :: Nil if file.toLowerCase.endsWith(zipSuffix) =>
      for
        family <- FamilyName.parse(file.dropRight(zipSuffix.length)).toOption
        sha    <- Sha256Digest.parse(digest)
      yield family -> sha
    case _                                                             => None

package io.worxbend.nerdfonts.releases

/**
 * A SHA-256 digest as 64 lowercase hex characters, so that a manifest entry and a freshly computed digest
 * compare with plain equality regardless of how the manifest spelled it.
 */
opaque type Sha256Digest = String

object Sha256Digest:
  private val hexLength   = 64
  private val digestBytes = 32

  /** Accepts 64 hex characters of either case (trimmed) and normalises them to lowercase. */
  def parse(raw: String): Option[Sha256Digest] =
    Option(raw.trim.toLowerCase).filter(text => text.length == hexLength && text.forall(isHexDigit))

  /** The digest of a finished `MessageDigest.digest()`; anything but 32 bytes is a programming error. */
  def fromBytes(bytes: Array[Byte]): Sha256Digest =
    require(bytes.length == digestBytes, s"a SHA-256 digest has $digestBytes bytes, got ${bytes.length}")
    bytes.map(byte => f"${byte & 0xff}%02x").mkString

  private def isHexDigit(c: Char): Boolean = c >= '0' && c <= '9' || c >= 'a' && c <= 'f'

  extension (digest: Sha256Digest)
    /** The lowercase hex text. */
    def value: String = digest

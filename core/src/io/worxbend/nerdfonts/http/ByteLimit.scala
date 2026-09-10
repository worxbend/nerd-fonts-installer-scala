package io.worxbend.nerdfonts.http

/**
 * An upper bound on how many bytes a download, a page or an archive entry may contain.
 *
 * Every cap in the application is one of these so the arithmetic (`limit + 1` detection, declared-size
 * checks) lives next to the type and every error message renders the number the same way.
 */
opaque type ByteLimit = Long

object ByteLimit:
  /** A cap in bytes; a negative value is a programming error, not input, so it is rejected loudly. */
  def bytes(value: Long): ByteLimit =
    require(value >= 0, s"byte limit must not be negative: $value")
    value

  def mebibytes(value: Long): ByteLimit = bytes(value * 1024L * 1024L)
  def gibibytes(value: Long): ByteLimit = bytes(value * 1024L * 1024L * 1024L)

  extension (limit: ByteLimit)
    /** The cap in bytes. */
    def value: Long = limit

    /** Whether a byte count (a `Content-Length`, a declared entry size, a running total) breaks the cap. */
    def exceededBy(count: Long): Boolean = count > limit

    /** The plain number, which is how the Go messages print a limit (`exceeds 805306368 byte limit`). */
    def render: String = limit.toString

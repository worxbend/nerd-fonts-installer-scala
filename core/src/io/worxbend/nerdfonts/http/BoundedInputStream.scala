package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.discard

import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Caps how many bytes can be read from an underlying stream, the same way Go's `io.LimitReader(body, max+1)`
 * backstops a missing or dishonest `Content-Length`.
 *
 * In `Overflow.Reject` mode the cap is `limit + 1`: reading one byte past the limit is allowed precisely so
 * that [[exceeded]] can tell "exactly at the limit" from "over it" without buffering anything. In
 * `Overflow.Truncate` mode the stream reports EOF at the limit and the excess is never read.
 *
 * The HTTP port caps its own bodies with a chunk-aware `ZStream` operator instead (see
 * [[ResponseDelivery.cappedBody]]); this class now backs only [[io.worxbend.nerdfonts.install.ArchiveExtractor]],
 * which reads a local, already-downloaded zip through a plain synchronous `InputStream`. The counter is an
 * `AtomicLong` only to avoid a mutable field; a stream is used from one thread at a time.
 */
final class BoundedInputStream(underlying: InputStream, limit: ByteLimit, overflow: Overflow)
    extends InputStream:
  private val cap: Long = overflow match
    case Overflow.Reject   => limit.value + 1
    case Overflow.Truncate => limit.value

  private val count = AtomicLong(0L)

  /** Bytes handed out so far. */
  def bytesRead: Long = count.get()

  /** Whether more bytes than the limit were read; only ever true in `Overflow.Reject` mode. */
  def exceeded: Boolean = limit.exceededBy(count.get())

  override def read(): Int =
    if remaining <= 0 then -1
    else
      val byte = underlying.read()
      if byte >= 0 then count.incrementAndGet().discard
      byte

  override def read(buffer: Array[Byte], offset: Int, length: Int): Int =
    if length == 0 then 0
    else if remaining <= 0 then -1
    else
      val read = underlying.read(buffer, offset, math.min(length.toLong, remaining).toInt)
      if read > 0 then count.addAndGet(read.toLong).discard
      read

  override def available(): Int = math.min(underlying.available().toLong, remaining).toInt

  override def close(): Unit = underlying.close()

  private def remaining: Long = cap - count.get()

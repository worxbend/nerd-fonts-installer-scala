package io.worxbend.nerdfonts.http

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

final class BoundedInputStreamSuite extends munit.FunSuite:
  private val limit = ByteLimit.bytes(4)

  private def bounded(content: String, overflow: Overflow): BoundedInputStream =
    BoundedInputStream(ByteArrayInputStream(content.getBytes), limit, overflow)

  test("reject mode hands out one byte past the limit and flags the excess"):
    val stream = bounded("abcdefgh", Overflow.Reject)
    assertEquals(String(stream.readAllBytes()), "abcde")
    assertEquals(stream.bytesRead, 5L)
    assert(stream.exceeded)

  test("reject mode does not flag a body that is exactly the limit"):
    val stream = bounded("abcd", Overflow.Reject)
    assertEquals(String(stream.readAllBytes()), "abcd")
    assert(!stream.exceeded)

  test("truncate mode ends the stream at the limit and never flags"):
    val stream = bounded("abcdefgh", Overflow.Truncate)
    assertEquals(String(stream.readAllBytes()), "abcd")
    assert(!stream.exceeded)

  test("single-byte reads respect the cap too"):
    val stream = bounded("abcdefgh", Overflow.Truncate)
    val bytes  = Iterator.continually(stream.read()).takeWhile(_ >= 0).map(_.toChar).mkString
    assertEquals(bytes, "abcd")

  test("a zero-length read returns zero without touching the underlying stream"):
    val stream = bounded("abcd", Overflow.Reject)
    assertEquals(stream.read(Array.ofDim[Byte](8), 0, 0), 0)
    assertEquals(stream.bytesRead, 0L)

  test("closing closes the underlying stream"):
    val closed     = AtomicBoolean(false)
    val underlying = new ByteArrayInputStream("x".getBytes):
      override def close(): Unit = closed.set(true)
    BoundedInputStream(underlying, limit, Overflow.Reject).close()
    assert(closed.get())

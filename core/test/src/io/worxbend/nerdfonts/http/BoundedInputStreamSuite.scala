package io.worxbend.nerdfonts.http

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

object BoundedInputStreamSuite extends ZIOSpecDefault:
  private val limit = ByteLimit.bytes(4)

  private def bounded(content: String, overflow: Overflow): BoundedInputStream =
    BoundedInputStream(ByteArrayInputStream(content.getBytes), limit, overflow)

  def spec = suite("BoundedInputStream")(
    test("reject mode hands out one byte past the limit and flags the excess"):
      val stream = bounded("abcdefgh", Overflow.Reject)
      assertTrue(
        String(stream.readAllBytes()) == "abcde",
        stream.bytesRead == 5L,
        stream.exceeded,
      )
    ,
    test("reject mode does not flag a body that is exactly the limit"):
      val stream = bounded("abcd", Overflow.Reject)
      assertTrue(
        String(stream.readAllBytes()) == "abcd",
        !stream.exceeded,
      )
    ,
    test("truncate mode ends the stream at the limit and never flags"):
      val stream = bounded("abcdefgh", Overflow.Truncate)
      assertTrue(
        String(stream.readAllBytes()) == "abcd",
        !stream.exceeded,
      )
    ,
    test("single-byte reads respect the cap too"):
      val stream = bounded("abcdefgh", Overflow.Truncate)
      val bytes  = Iterator.continually(stream.read()).takeWhile(_ >= 0).map(_.toChar).mkString
      assertTrue(bytes == "abcd")
    ,
    test("a zero-length read returns zero without touching the underlying stream"):
      val stream = bounded("abcd", Overflow.Reject)
      assertTrue(
        stream.read(Array.ofDim[Byte](8), 0, 0) == 0,
        stream.bytesRead == 0L,
      )
    ,
    test("closing closes the underlying stream"):
      val closed     = AtomicBoolean(false)
      val underlying = new ByteArrayInputStream("x".getBytes):
        override def close(): Unit = closed.set(true)
      BoundedInputStream(underlying, limit, Overflow.Reject).close()
      assertTrue(closed.get()),
  )

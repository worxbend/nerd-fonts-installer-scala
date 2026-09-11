package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.picker.PickerKey.*

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.LockSupport

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt

/** The §7 byte → key table, including the escape-timeout race. */
final class KeyDecoderSuite extends munit.FunSuite:
  private def decoder(bytes: Array[Byte]): KeyDecoder = KeyDecoder(ByteArrayInputStream(bytes), 50.millis)

  private def keys(text: String): Vector[PickerKey] =
    drain(decoder(text.getBytes(StandardCharsets.UTF_8)), Vector.empty)

  @tailrec
  private def drain(decoder: KeyDecoder, acc: Vector[PickerKey]): Vector[PickerKey] = decoder.readKey() match
    case None      => acc
    case Some(key) => drain(decoder, acc :+ key)

  test("control bytes decode to their named keys"):
    assertEquals(
      keys("\u0003\n\r\t\b "),
      Vector(CtrlC, CtrlJ, CtrlK, Enter, Tab, Backspace, Backspace, Space),
    )

  test("printable ASCII decodes to characters"):
    assertEquals(keys("qa/"), Vector(Char('q'), Char('a'), Char('/')))

  test("a bare escape at end of input is Escape"):
    assertEquals(keys("\u001b"), Vector(Escape))

  test("an escape followed by silence is Escape after the timeout"):
    val input = BlockingAfter(Array(0x1b.toByte))
    assertEquals(KeyDecoder(input, 30.millis).readKey(), Some(Escape))

  test("CSI arrows, home, end and shift-tab decode"):
    assertEquals(
      keys("\u001b[A\u001b[B\u001b[C\u001b[D\u001b[H\u001b[F\u001b[Z"),
      Vector(Up, Down, Right, Left, Home, End, ShiftTab),
    )

  test("SS3 arrows decode like CSI ones"):
    assertEquals(keys("\u001bOA\u001bOB"), Vector(Up, Down))

  test("tilde sequences decode to paging and home/end"):
    assertEquals(keys("\u001b[5~\u001b[6~\u001b[1~\u001b[4~"), Vector(PageUp, PageDown, Home, End))

  test("an unknown escape sequence is dropped without leaking its bytes"):
    assertEquals(keys("\u001b[1;5Aq"), Vector(Char('q')))

  test("alt-modified characters are dropped"):
    assertEquals(keys("\u001bxq"), Vector(Char('q')))

  test("multi-byte UTF-8 decodes to a single character"):
    assertEquals(keys("é€"), Vector(Char('é'), Char('€')))

  test("a supplementary code point is dropped and decoding continues"):
    assertEquals(keys("🚀q"), Vector(Char('q')))

  test("a stray continuation byte is dropped"):
    assertEquals(drain(decoder(Array(0x80.toByte, 'q'.toByte)), Vector.empty), Vector(Char('q')))

  test("a truncated multi-byte sequence at end of input ends the stream"):
    assertEquals(drain(decoder(Array(0xc3.toByte)), Vector.empty), Vector.empty)

  test("end of input is None"):
    assertEquals(decoder(Array.empty).readKey(), None)

  /** Serves the given bytes, then parks every further read until the reading thread is interrupted. */
  final private class BlockingAfter(bytes: Array[Byte]) extends InputStream:
    private val served = ByteArrayInputStream(bytes)

    def read(): Int = served.read() match
      case -1    =>
        LockSupport.park()
        if Thread.interrupted() then throw InterruptedException() else -1
      case value => value

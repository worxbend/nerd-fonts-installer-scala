package io.worxbend.nerdfonts.picker

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

import ox.either.catching
import ox.timeoutOption

/**
 * Turns the raw-mode byte stream into `PickerKey`s.
 *
 * A lone `ESC` and the start of an escape sequence are the same byte; the only way to tell them apart is
 * whether more bytes follow promptly, hence the `escapeTimeout` race on the byte after `ESC`. That race is
 * what requires the input to be interruptible (`StdinSource`): a plain `System.in` read would ignore the
 * timeout's interrupt and the decoder would hang until the next key. Sequences the picker has no use for
 * (function keys, modified arrows, mouse reports) are consumed and dropped so their bytes never leak into
 * the filter as text.
 */
final private[picker] class KeyDecoder(input: InputStream, escapeTimeout: FiniteDuration):
  import KeyDecoder.*

  /** The next key; `None` when the input is exhausted. */
  @tailrec
  def readKey(): Option[PickerKey] = read() match
    case Read.EndOfInput  => None
    case Read.Byte(value) => decode(value) match
        case Decoded.Key(key)   => Some(key)
        case Decoded.Ignored    => readKey()
        case Decoded.EndOfInput => None

  private def decode(byte: Int): Decoded = byte match
    case 0x03          => Decoded.Key(PickerKey.CtrlC)
    case 0x0a          => Decoded.Key(PickerKey.CtrlJ)
    case 0x0b          => Decoded.Key(PickerKey.CtrlK)
    case 0x0d          => Decoded.Key(PickerKey.Enter)
    case 0x09          => Decoded.Key(PickerKey.Tab)
    case 0x7f | 0x08   => Decoded.Key(PickerKey.Backspace)
    case 0x20          => Decoded.Key(PickerKey.Space)
    case 0x1b          => decodeEscape()
    case b if b < 0x80 => Decoded.Key(PickerKey.Char(b.toChar))
    case lead          => decodeUtf8(lead)

  // Timeout or end of input after ESC means the user pressed Escape itself.
  private def decodeEscape(): Decoded = timeoutOption(escapeTimeout)(read()) match
    case None | Some(Read.EndOfInput) => Decoded.Key(PickerKey.Escape)
    case Some(Read.Byte('['))         => decodeCsi(Vector.empty)
    case Some(Read.Byte('O'))         => decodeSs3()
    case Some(Read.Byte(_))           => Decoded.Ignored

  // CSI: parameter and intermediate bytes (0x20–0x3F) up to one final byte (0x40–0x7E).
  @tailrec
  private def decodeCsi(parameters: Vector[Int]): Decoded = read() match
    case Read.EndOfInput                                   => Decoded.EndOfInput
    case Read.Byte(value) if value >= 0x20 && value < 0x40 => decodeCsi(parameters :+ value)
    case Read.Byte(value)                                  => csiKey(parameters.map(_.toChar).mkString, value.toChar)

  private def csiKey(parameters: String, terminator: Char): Decoded = (parameters, terminator) match
    case ("", 'A')  => Decoded.Key(PickerKey.Up)
    case ("", 'B')  => Decoded.Key(PickerKey.Down)
    case ("", 'C')  => Decoded.Key(PickerKey.Right)
    case ("", 'D')  => Decoded.Key(PickerKey.Left)
    case ("", 'H')  => Decoded.Key(PickerKey.Home)
    case ("", 'F')  => Decoded.Key(PickerKey.End)
    case ("", 'Z')  => Decoded.Key(PickerKey.ShiftTab)
    case ("5", '~') => Decoded.Key(PickerKey.PageUp)
    case ("6", '~') => Decoded.Key(PickerKey.PageDown)
    case ("1", '~') => Decoded.Key(PickerKey.Home)
    case ("4", '~') => Decoded.Key(PickerKey.End)
    case _          => Decoded.Ignored

  private def decodeSs3(): Decoded = read() match
    case Read.EndOfInput  => Decoded.EndOfInput
    case Read.Byte(value) => csiKey("", value.toChar)

  private def decodeUtf8(lead: Int): Decoded = utf8Length(lead) match
    case None         => Decoded.Ignored
    case Some(length) => continuation(Vector(lead.toByte), length - 1)

  @tailrec
  private def continuation(bytes: Vector[Byte], remaining: Int): Decoded =
    if remaining == 0 then utf8Char(bytes)
    else
      read() match
        case Read.EndOfInput                            => Decoded.EndOfInput
        case Read.Byte(value) if (value & 0xc0) == 0x80 => continuation(bytes :+ value.toByte, remaining - 1)
        case Read.Byte(_)                               => Decoded.Ignored

  private def utf8Char(bytes: Vector[Byte]): Decoded = utf8
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes.toArray))
    .catching[CharacterCodingException]
    .toOption
    .map(_.toString)
    .collect { case text if text.length == 1 => Decoded.Key(PickerKey.Char(text.charAt(0))) }
    .getOrElse(Decoded.Ignored)

  private def read(): Read = input.read() match
    case -1    => Read.EndOfInput
    case value => Read.Byte(value)

private[picker] object KeyDecoder:
  private val utf8 = StandardCharsets.UTF_8

  private def utf8Length(lead: Int): Option[Int] =
    if (lead & 0xe0) == 0xc0 then Some(2)
    else if (lead & 0xf0) == 0xe0 then Some(3)
    else if (lead & 0xf8) == 0xf0 then Some(4)
    else None

  /** One `read()` result, named so `-1` never travels as an integer. */
  private enum Read:
    case EndOfInput
    case Byte(value: Int)

  /** What one byte sequence decoded to. */
  private enum Decoded:
    case Key(key: PickerKey)
    case Ignored
    case EndOfInput

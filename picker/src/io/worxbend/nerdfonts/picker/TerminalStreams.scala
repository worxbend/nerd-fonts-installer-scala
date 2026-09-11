package io.worxbend.nerdfonts.picker

import java.io.InputStream
import java.io.OutputStream

/**
 * Where `SttyTerminal` reads keys from and paints frames to. Injected so the adapter's output discipline
 * (`\r\n`, alternate screen, cursor) can be asserted byte for byte against an in-memory sink.
 */
final case class TerminalStreams(input: InputStream, output: OutputStream)

object TerminalStreams:
  /** The process's own terminal: the shared interruptible stdin and stdout. */
  def process: TerminalStreams = TerminalStreams(StdinSource.stream, System.out)

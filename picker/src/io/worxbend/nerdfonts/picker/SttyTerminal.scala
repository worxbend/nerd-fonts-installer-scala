package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.process.ProcessRunner
import io.worxbend.nerdfonts.process.ProcessSpec
import io.worxbend.nerdfonts.process.Stdin
import io.worxbend.nerdfonts.process.Stdout

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import ox.discard

/**
 * The production `Terminal`: raw mode through `stty`, frames through ANSI on stdout.
 *
 * `stty` is driven through the `ProcessRunner` port with its stdin redirected from `/dev/tty` — never a
 * shell — so tests can script it and a destination containing shell syntax can never reach one. The saved
 * `stty -g` string is opaque (Linux and macOS formats differ) and is only ever handed back to `stty`.
 *
 * Raw mode clears `opost`/`onlcr`, so the terminal no longer expands `\n` into a carriage return plus line
 * feed; every line break this adapter writes is therefore an explicit `\r\n`, and a frame is one flushed
 * write so a partially painted screen is never visible.
 */
final class SttyTerminal(
    processes: ProcessRunner,
    escapeTimeout: FiniteDuration = SttyTerminal.defaultEscapeTimeout,
    streams: TerminalStreams = TerminalStreams.process,
) extends Terminal:
  import SttyTerminal.*

  // `saved` is captured before anything else runs, so it must be restored on every exit from this point on:
  // a normal return, the body throwing, or `stty raw -echo` itself throwing (an `InterruptedException` can
  // surface from `Process.waitFor()` after the child has already applied the termios change but before our
  // thread observes it returning) all reach the outer `finally`. The alternate screen and cursor sequences
  // stay inside the success branch so they are never emitted when raw mode was not actually entered.
  def withRawMode[A](body: RawTerminal => A): Either[TerminalError, A] = stty("-g").flatMap: saved =>
    try stty("raw", "-echo").map(_ => withScreen(body(RawSession())))
    finally stty(saved).discard

  private def withScreen[A](session: => A): A =
    try
      emit(enterScreen)
      session
    finally emit(leaveScreen)

  final private class RawSession extends RawTerminal:
    private val decoder = KeyDecoder(streams.input, escapeTimeout)

    def size(): Viewport = stty("size").toOption.flatMap(parseSize).getOrElse(Viewport.fallback)

    def readKey(): Option[PickerKey] = decoder.readKey()

    def write(frame: Frame): Unit = emit(framePaint(frame))

  private def stty(arguments: String*): Either[TerminalError, String] =
    val command = "stty" +: arguments.toVector
    val spec    = ProcessSpec(command, stdin = Stdin.FromFile(devTty), stdout = Stdout.Capture)
    processes.run(spec) match
      case Left(error)                            => Left(TerminalError.RawModeUnavailable(error.render))
      case Right(result) if result.exit.isSuccess => Right(result.stdout.trim)
      case Right(result)                          =>
        Left(TerminalError.RawModeUnavailable(s"${command.mkString(" ")}: ${result.exit.render}"))

  private def emit(text: String): Unit =
    streams.output.write(text.getBytes(StandardCharsets.UTF_8))
    streams.output.flush()

object SttyTerminal:
  /** How long the decoder waits for a byte after `ESC` before calling it a bare Escape. */
  val defaultEscapeTimeout: FiniteDuration = 50.millis

  val devTty: os.Path = os.Path("/dev/tty")

  private val escape: String      = "\u001b"
  private val enterScreen: String = s"$escape[?1049h$escape[?25l"
  private val leaveScreen: String = s"$escape[?25h$escape[?1049l"
  private val cursorHome: String  = s"$escape[H"
  private val clearToEnd: String  = s"$escape[K"
  private val clearBelow: String  = s"$escape[J"

  /** `ESC[H`, each line followed by `ESC[K`, lines joined with `\r\n`, then `ESC[J`. */
  private[picker] def framePaint(frame: Frame): String =
    cursorHome + frame.lines.map(_ + clearToEnd).mkString("\r\n") + clearBelow

  /** `stty size` prints `rows cols`; anything else falls back to 80×24. */
  private[picker] def parseSize(output: String): Option[Viewport] = output.trim.split("\\s+").toVector match
    case Vector(rows, cols) =>
      for
        height <- Try(rows.toInt).toOption if height > 0
        width  <- Try(cols.toInt).toOption if width > 0
      yield Viewport(width, height)
    case _                  => None

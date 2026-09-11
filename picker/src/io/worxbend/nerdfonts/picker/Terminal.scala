package io.worxbend.nerdfonts.picker

/**
 * The picker's only window onto a real terminal. Loan-shaped: raw mode, the alternate screen and the hidden
 * cursor exist exactly for the duration of `body`, and the adapter restores all three on every exit path,
 * including an interrupt unwinding through `body`. Nothing outside the loan can read a key or draw a frame.
 */
trait Terminal:
  def withRawMode[A](body: RawTerminal => A): Either[TerminalError, A]

/** What a session may do while the terminal is in raw mode. */
trait RawTerminal:
  /** The current size, queried per frame so a resize shows up on the next redraw. */
  def size(): Viewport

  /** The next decoded key, or `None` once the input has reached end of file. */
  def readKey(): Option[PickerKey]

  /** Paint one full frame from the top-left corner. */
  def write(frame: Frame): Unit

/** Why the terminal could not be put into raw mode; the cause is the `stty` failure text. */
enum TerminalError:
  case RawModeUnavailable(cause: String)

  def render: String = this match
    case RawModeUnavailable(cause) => s"enter raw terminal mode: $cause"

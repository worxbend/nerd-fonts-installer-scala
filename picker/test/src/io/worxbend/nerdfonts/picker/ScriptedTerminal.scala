package io.worxbend.nerdfonts.picker

import java.util.concurrent.atomic.AtomicReference

import ox.discard

/**
 * A `Terminal` for tests in every module: feeds a fixed key script, reports a fixed size, records every frame
 * and counts raw-mode entries and exits so a test can assert the loan was returned even when the body threw.
 * Once the script is exhausted `readKey` reports end of input, which the session treats as a cancellation.
 */
final class ScriptedTerminal(
    keys: Vector[PickerKey],
    viewport: Viewport = Viewport(120, 40),
    rawMode: Either[TerminalError, Unit] = Right(()),
) extends Terminal:
  private val pending  = AtomicReference(keys)
  private val recorded = AtomicReference(Vector.empty[Frame])
  private val entered  = AtomicReference(0)
  private val left     = AtomicReference(0)

  /** Every frame written, in order. */
  def frames: Vector[Frame] = recorded.get()

  /** How many times raw mode was entered and how many times it was restored. */
  def rawModeEntries: Int = entered.get()
  def rawModeExits: Int   = left.get()

  def withRawMode[A](body: RawTerminal => A): Either[TerminalError, A] = rawMode.map: _ =>
    entered.updateAndGet(_ + 1).discard
    try body(Session)
    finally left.updateAndGet(_ + 1).discard

  private object Session extends RawTerminal:
    def size(): Viewport = viewport

    def readKey(): Option[PickerKey] = pending.getAndUpdate(_.drop(1)).headOption

    def write(frame: Frame): Unit = recorded.updateAndGet(_ :+ frame).discard

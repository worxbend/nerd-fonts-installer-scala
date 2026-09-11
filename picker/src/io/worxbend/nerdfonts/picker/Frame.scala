package io.worxbend.nerdfonts.picker

/**
 * One fully rendered screen: the lines to paint from the top-left corner, styled or plain. Kept opaque so a
 * frame can only be produced by a renderer and consumed by a terminal; nothing else joins or splits lines,
 * which is what lets `SttyTerminal` own the `\r\n` rule.
 */
opaque type Frame = Vector[String]

object Frame:
  def apply(lines: Vector[String]): Frame = lines

  val empty: Frame = Vector.empty

  extension (frame: Frame)
    /** The lines top to bottom, without line terminators. */
    def lines: Vector[String] = frame

package io.worxbend.nerdfonts.picker

/**
 * The terminal's size in cells. Queried before every frame because a user may resize mid-session; the
 * layout floors (48×24) are applied by `Layout`, not here, so the raw measurement stays inspectable.
 */
final case class Viewport(width: Int, height: Int)

object Viewport:
  /** What the terminal is assumed to be when `stty size` cannot answer. */
  val fallback: Viewport = Viewport(80, 24)

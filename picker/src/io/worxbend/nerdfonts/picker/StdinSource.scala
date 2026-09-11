package io.worxbend.nerdfonts.picker

import java.io.InputStream

import ox.abandonOnInterruptReads

/**
 * The single interruptible view of `System.in` for the whole process.
 *
 * `abandonOnInterruptReads` reads on a detached thread and hands chunks over interruptibly, which is what
 * lets `KeyDecoder`'s escape timeout and an external SIGINT unblock a pending read. Two such wrappers would
 * compete for the same bytes, so there is exactly one, created on first use and never closed.
 */
private[picker] object StdinSource:
  lazy val stream: InputStream = abandonOnInterruptReads(System.in)

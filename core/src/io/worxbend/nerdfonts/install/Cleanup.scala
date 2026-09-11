package io.worxbend.nerdfonts.install

import scala.util.Try

import ox.discard

/**
 * Best-effort removal of the engine's own scratch files, matching Go's `_ = os.Remove(...)`.
 *
 * These run in `finally` blocks and after the commit point of a swap, where a failure must neither mask
 * the real error nor turn a completed install into a reported one; a leftover is harmless and is cleared
 * by the next run. Only non-fatal exceptions are swallowed, so an interrupt still propagates.
 */
private[install] object Cleanup:
  def removeFile(path: os.Path): Unit = Try(os.remove(path, checkExists = false)).discard

  def removeTree(path: os.Path): Unit =
    Try(if os.exists(path, followLinks = false) then os.remove.all(path)).discard

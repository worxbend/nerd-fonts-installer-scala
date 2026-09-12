package io.worxbend.nerdfonts.install

import zio.UIO
import zio.ZIO

/**
 * Best-effort removal of the engine's own scratch files; the outcome is deliberately ignored.
 *
 * These run after the commit point of a swap and in cleanup paths, where a failure must neither mask the
 * real error nor turn a completed install into a reported one; a leftover is harmless and is cleared by the
 * next run. `attemptBlockingIO` runs the removal on the blocking pool and only swallows `IOException` (a
 * missing file, a permission problem, a non-empty directory); any other defect, and fiber interruption,
 * still propagate.
 */
private[install] object Cleanup:
  def removeFile(path: os.Path): UIO[Unit] =
    ZIO.attemptBlockingIO(os.remove(path, checkExists = false)).ignore

  def removeTree(path: os.Path): UIO[Unit] =
    ZIO.attemptBlockingIO(if os.exists(path, followLinks = false) then os.remove.all(path)).ignore

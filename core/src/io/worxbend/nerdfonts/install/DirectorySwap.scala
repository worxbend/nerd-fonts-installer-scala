package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.Diagnostics

import zio.IO
import zio.UIO
import zio.ZIO

/**
 * Atomically replaces a family directory with a freshly extracted one.
 *
 * The sequence is: remove a stale `<target>.old`, rename the existing target to `.old`, rename the staging
 * directory into place, then best-effort delete `.old`. The second rename is the commit point: before it the
 * previous fonts are restored on failure, after it a cleanup failure is never reported, because the new fonts
 * are already live and a leftover `.old` is harmless (the next run removes it first).
 *
 * Every filesystem call runs through `ZIO.attemptBlockingIO` on the blocking pool; only `IOException` is
 * turned into a `SwapError`, so a defect (a bug, not a filesystem condition) still crashes the fiber instead
 * of being reported as a swap failure — matching the direct-style code, which let anything besides the
 * explicitly caught `IOException` propagate uncaught.
 */
object DirectorySwap:
  private val backupSuffix = ".old"

  def replace(staging: os.Path, target: os.Path): IO[SwapError, Unit] =
    val backup = backupOf(target)
    for
      _ <- removeStale(backup)
      // `moveAside` and `commit` must not be separated by an interruption checkpoint. Between them the
      // user's existing fonts live only at `<target>.old`, and the caller's `ensuring` finalizers delete
      // the staging directory on the way out, so an interrupt landing in that window would leave no
      // `<target>` at all -- and the next run's `removeStale` would then delete the backup. Both steps are
      // bounded `rename(2)` calls, so making them uninterruptible does not create an unbounded region.
      // The trigger is not only SIGINT: a failing sibling family and the per-family deadline both
      // interrupt this fiber at exactly these checkpoints.
      _ <- ZIO.uninterruptible(moveAside(target, backup).flatMap(commit(staging, target, backup, _)))
    yield ()

  private def backupOf(target: os.Path): os.Path = target / os.up / s"${target.last}$backupSuffix"

  private def removeStale(backup: os.Path): IO[SwapError, Unit] = ZIO
    .attemptBlockingIO(if os.exists(backup, followLinks = false) then os.remove.all(backup))
    .mapError(error => SwapError.RemoveBackup(backup, Diagnostics.describe(error)))

  private def moveAside(target: os.Path, backup: os.Path): IO[SwapError, Previous] = ZIO
    .attemptBlockingIO(os.exists(target))
    .orDie
    .flatMap: exists =>
      if !exists then ZIO.succeed(Previous.Absent)
      else
        ZIO
          .attemptBlockingIO(os.move(target, backup, atomicMove = true))
          .as(Previous.MovedAside)
          .mapError(error => SwapError.MoveAside(target, backup, Diagnostics.describe(error)))

  private def commit(
      staging: os.Path,
      target: os.Path,
      backup: os.Path,
      previous: Previous,
  ): IO[SwapError, Unit] = ZIO
    .attemptBlockingIO(os.move(staging, target, atomicMove = true))
    .foldZIO(
      error =>
        restore(previous, backup, target) *> ZIO
          .fail(SwapError.MoveInto(staging, target, Diagnostics.describe(error))),
      _ => Cleanup.removeTree(backup),
    )

  private def restore(previous: Previous, backup: os.Path, target: os.Path): UIO[Unit] = previous match
    case Previous.MovedAside => ZIO.attemptBlockingIO(os.move(backup, target, atomicMove = true)).ignore
    case Previous.Absent     => ZIO.unit

  /** Whether a previous install was moved to the backup, which decides if a failed commit has anything to restore. */
  private enum Previous:
    case MovedAside, Absent

/** Why the swap failed; every case names both paths in its message. */
enum SwapError:
  case RemoveBackup(backup: os.Path, cause: String)
  case MoveAside(target: os.Path, backup: os.Path, cause: String)
  case MoveInto(staging: os.Path, target: os.Path, cause: String)

  def render: String = this match
    case RemoveBackup(backup, cause)      => s"remove old backup $backup: $cause"
    case MoveAside(target, backup, cause) => s"move existing destination $target to $backup: $cause"
    case MoveInto(staging, target, cause) => s"move extracted fonts $staging to $target: $cause"

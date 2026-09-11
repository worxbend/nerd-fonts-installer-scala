package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.Diagnostics

import java.io.IOException

import scala.util.Try

import ox.discard
import ox.either
import ox.either.catching
import ox.either.ok

/**
 * Atomically replaces a family directory with a freshly extracted one.
 *
 * The sequence is: remove a stale `<target>.old`, rename the existing target to `.old`, rename the staging
 * directory into place, then best-effort delete `.old`. The second rename is the commit point: before it the
 * previous fonts are restored on failure, after it a cleanup failure is never reported, because the new fonts
 * are already live and a leftover `.old` is harmless (the next run removes it first).
 */
object DirectorySwap:
  private val backupSuffix = ".old"

  def replace(staging: os.Path, target: os.Path): Either[SwapError, Unit] =
    val backup = backupOf(target)
    either:
      removeStale(backup).ok()
      val previous = moveAside(target, backup).ok()
      commit(staging, target, backup, previous).ok()

  private def backupOf(target: os.Path): os.Path = target / os.up / s"${target.last}$backupSuffix"

  private def removeStale(backup: os.Path): Either[SwapError, Unit] =
    (if os.exists(backup, followLinks = false) then os.remove.all(backup))
      .catching[IOException]
      .left
      .map(error => SwapError.RemoveBackup(backup, Diagnostics.describe(error)))

  private def moveAside(target: os.Path, backup: os.Path): Either[SwapError, Previous] =
    if !os.exists(target) then Right(Previous.Absent)
    else
      os.move(target, backup, atomicMove = true)
        .catching[IOException]
        .map(_ => Previous.MovedAside)
        .left
        .map(error => SwapError.MoveAside(target, backup, Diagnostics.describe(error)))

  private def commit(
      staging: os.Path,
      target: os.Path,
      backup: os.Path,
      previous: Previous,
  ): Either[SwapError, Unit] = os.move(staging, target, atomicMove = true).catching[IOException] match
    case Right(())   => Right(Cleanup.removeTree(backup))
    case Left(error) =>
      restore(previous, backup, target)
      Left(SwapError.MoveInto(staging, target, Diagnostics.describe(error)))

  private def restore(previous: Previous, backup: os.Path, target: os.Path): Unit = previous match
    case Previous.MovedAside => Try(os.move(backup, target, atomicMove = true)).discard
    case Previous.Absent     => ()

  /** Whether a previous install was moved to the backup, which decides if a failed commit has anything to restore. */
  private enum Previous:
    case MovedAside, Absent

/** Why the swap failed; every case names both paths as the Go messages do. */
enum SwapError:
  case RemoveBackup(backup: os.Path, cause: String)
  case MoveAside(target: os.Path, backup: os.Path, cause: String)
  case MoveInto(staging: os.Path, target: os.Path, cause: String)

  def render: String = this match
    case RemoveBackup(backup, cause)      => s"remove old backup $backup: $cause"
    case MoveAside(target, backup, cause) => s"move existing destination $target to $backup: $cause"
    case MoveInto(staging, target, cause) => s"move extracted fonts $staging to $target: $cause"

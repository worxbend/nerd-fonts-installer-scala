package io.worxbend.nerdfonts

import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/**
 * Turns a caught exception into the `<cause>` text of an error message. Kept in one place so every adapter
 * describes platform failures the same way, and so a `null` or empty message never produces a blank cause.
 *
 * `java.nio.file.FileSystemException` is special-cased: the JDK's Unix implementation builds
 * `AccessDeniedException`, `NoSuchFileException` and `FileAlreadyExistsException` with a `null` reason, so
 * `getMessage` on the plain path is `Some(<path>)` — not empty, so the fallback below never triggers, but
 * useless (`create destination /root/fonts: /root/fonts`). Go wraps the OS error and gets `permission denied`
 * / `no such file or directory` / `file exists`; `getReason` carries exactly that text when the JDK does set
 * it (a case this fallback also covers), so it is preferred over guessing from the exception's class.
 */
private[nerdfonts] object Diagnostics:
  def describe(error: Throwable): String = error match
    case fileSystemError: FileSystemException => fileSystemReason(fileSystemError)
    case other                                => message(other)

  private def fileSystemReason(error: FileSystemException): String =
    Option(error.getReason).map(_.trim).filter(_.nonEmpty).getOrElse(fileSystemFallback(error))

  private def fileSystemFallback(error: FileSystemException): String = error match
    case _: AccessDeniedException      => "permission denied"
    case _: NoSuchFileException        => "no such file or directory"
    case _: FileAlreadyExistsException => "file exists"
    case _: NotDirectoryException      => "not a directory"
    case _: DirectoryNotEmptyException => "directory not empty"
    case other                         => message(other)

  private def message(error: Throwable): String =
    Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

package io.worxbend.nerdfonts

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/**
 * The JDK's Unix `FileSystemException`s carry no reason (`getMessage` is just the path), unlike Go's wrapped
 * OS errors (`permission denied`, `no such file or directory`, `file exists`); every case here is
 * constructed directly, without touching the real filesystem, so the test does not depend on the user
 * running it being unprivileged.
 */
final class DiagnosticsSuite extends munit.FunSuite:
  test("an ordinary exception describes itself by its trimmed message"):
    assertEquals(Diagnostics.describe(IOException("connection reset")), "connection reset")

  test("a blank or missing message falls back to the exception's class name"):
    assertEquals(Diagnostics.describe(IOException()), "IOException")
    assertEquals(Diagnostics.describe(IOException("   ")), "IOException")

  test("AccessDeniedException with no reason renders permission denied"):
    assertEquals(Diagnostics.describe(AccessDeniedException("/root/fonts")), "permission denied")

  test("NoSuchFileException with no reason renders no such file or directory"):
    assertEquals(Diagnostics.describe(NoSuchFileException("/missing")), "no such file or directory")

  test("FileAlreadyExistsException with no reason renders file exists"):
    assertEquals(Diagnostics.describe(FileAlreadyExistsException("/fonts/Hack")), "file exists")

  test("NotDirectoryException with no reason renders not a directory"):
    assertEquals(Diagnostics.describe(NotDirectoryException("/fonts/Hack")), "not a directory")

  test("DirectoryNotEmptyException with no reason renders directory not empty"):
    assertEquals(Diagnostics.describe(DirectoryNotEmptyException("/fonts")), "directory not empty")

  test("a FileSystemException that does carry a reason uses it verbatim"):
    val error = AccessDeniedException("/root/fonts", "", "custom reason")
    assertEquals(Diagnostics.describe(error), "custom reason")

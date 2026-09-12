package io.worxbend.nerdfonts

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * The JDK's Unix `FileSystemException`s carry no reason (`getMessage` is just the path), unlike Go's wrapped
 * OS errors (`permission denied`, `no such file or directory`, `file exists`); every case here is
 * constructed directly, without touching the real filesystem, so the test does not depend on the user
 * running it being unprivileged.
 */
object DiagnosticsSuite extends ZIOSpecDefault:
  def spec = suite("Diagnostics")(
    test("an ordinary exception describes itself by its trimmed message"):
      assertTrue(Diagnostics.describe(IOException("connection reset")) == "connection reset")
    ,
    test("a blank or missing message falls back to the exception's class name"):
      assertTrue(
        Diagnostics.describe(IOException()) == "IOException",
        Diagnostics.describe(IOException("   ")) == "IOException",
      )
    ,
    test("AccessDeniedException with no reason renders permission denied"):
      assertTrue(Diagnostics.describe(AccessDeniedException("/root/fonts")) == "permission denied")
    ,
    test("NoSuchFileException with no reason renders no such file or directory"):
      assertTrue(Diagnostics.describe(NoSuchFileException("/missing")) == "no such file or directory")
    ,
    test("FileAlreadyExistsException with no reason renders file exists"):
      assertTrue(Diagnostics.describe(FileAlreadyExistsException("/fonts/Hack")) == "file exists")
    ,
    test("NotDirectoryException with no reason renders not a directory"):
      assertTrue(Diagnostics.describe(NotDirectoryException("/fonts/Hack")) == "not a directory")
    ,
    test("DirectoryNotEmptyException with no reason renders directory not empty"):
      assertTrue(Diagnostics.describe(DirectoryNotEmptyException("/fonts")) == "directory not empty")
    ,
    test("a FileSystemException that does carry a reason uses it verbatim"):
      val error = AccessDeniedException("/root/fonts", "", "custom reason")
      assertTrue(Diagnostics.describe(error) == "custom reason"),
  )

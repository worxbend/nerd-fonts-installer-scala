package io.worxbend.nerdfonts.install

final class DirectorySwapSuite extends munit.FunSuite:
  private val tempDir = FunFixture[os.Path](_ => os.temp.dir(prefix = "directory-swap"), os.remove.all(_))

  private def staged(dir: os.Path, font: String): os.Path =
    val staging = dir / ".Hack-staging"
    os.write(staging / "new.ttf", font, createFolders = true)
    staging

  tempDir.test("moves the staging directory into place when no target exists"): dir =>
    val staging = staged(dir, "new")
    assertEquals(DirectorySwap.replace(staging, dir / "Hack"), Right(()))
    assertEquals(os.read(dir / "Hack" / "new.ttf"), "new")
    assert(!os.exists(staging))

  tempDir.test("replaces an existing target and removes the backup"): dir =>
    os.write(dir / "Hack" / "old.ttf", "old", createFolders = true)
    assertEquals(DirectorySwap.replace(staged(dir, "new"), dir / "Hack"), Right(()))
    assertEquals(os.list(dir / "Hack").map(_.last), Seq("new.ttf"))
    assert(!os.exists(dir / "Hack.old"))

  tempDir.test("removes a stale backup left by an earlier run before swapping"): dir =>
    os.write(dir / "Hack.old" / "stale.ttf", "stale", createFolders = true)
    assertEquals(DirectorySwap.replace(staged(dir, "new"), dir / "Hack"), Right(()))
    assert(!os.exists(dir / "Hack.old"))

  tempDir.test("rolls the previous install back when the final rename fails"): dir =>
    val target = dir / "Hack"
    os.write(target / "keep.ttf", "original", createFolders = true)
    val result = DirectorySwap.replace(dir / "does-not-exist", target)
    assert(result.left.exists(_.isInstanceOf[SwapError.MoveInto]), result.toString)
    assertEquals(os.read(target / "keep.ttf"), "original")
    assert(!os.exists(dir / "Hack.old"))

  tempDir.test("a failed rename without a previous install leaves nothing behind"): dir =>
    val result = DirectorySwap.replace(dir / "does-not-exist", dir / "Hack")
    assert(result.isLeft)
    assertEquals(os.list(dir), Seq.empty)

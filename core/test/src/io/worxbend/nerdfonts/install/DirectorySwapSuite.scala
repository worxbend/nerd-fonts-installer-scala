package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.install.EagerAssertion.eagerly

import zio.Task
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object DirectorySwapSuite extends ZIOSpecDefault:
  private def withTempDir[A](test: os.Path => Task[A]): Task[A] = ZIO.acquireReleaseWith(
    ZIO.attemptBlockingIO(os.temp.dir(prefix = "directory-swap")),
  )(dir => ZIO.attemptBlockingIO(os.remove.all(dir)).orDie)(test)

  private def staged(dir: os.Path, font: String): os.Path =
    val staging = dir / ".Hack-staging"
    os.write(staging / "new.ttf", font, createFolders = true)
    staging

  override def spec: Spec[TestEnvironment, Any] = suite("DirectorySwap")(
    test("moves the staging directory into place when no target exists"):
      withTempDir { dir =>
        val staging = staged(dir, "new")
        DirectorySwap.replace(staging, dir / "Hack").either.map { result =>
          eagerly(
            assertTrue(
              result == Right(()),
              os.read(dir / "Hack" / "new.ttf") == "new",
              !os.exists(staging),
            ),
          )
        }
      }
    ,
    test("replaces an existing target and removes the backup"):
      withTempDir { dir =>
        ZIO.attemptBlockingIO(os.write(dir / "Hack" / "old.ttf", "old", createFolders = true)) *>
          DirectorySwap.replace(staged(dir, "new"), dir / "Hack").either.map { result =>
            eagerly(
              assertTrue(
                result == Right(()),
                os.list(dir / "Hack").map(_.last) == Seq("new.ttf"),
                !os.exists(dir / "Hack.old"),
              ),
            )
          }
      }
    ,
    test("removes a stale backup left by an earlier run before swapping"):
      withTempDir { dir =>
        ZIO.attemptBlockingIO(os.write(dir / "Hack.old" / "stale.ttf", "stale", createFolders = true)) *>
          DirectorySwap.replace(staged(dir, "new"), dir / "Hack").either.map { result =>
            eagerly(assertTrue(result == Right(()), !os.exists(dir / "Hack.old")))
          }
      }
    ,
    test("rolls the previous install back when the final rename fails"):
      withTempDir { dir =>
        val target = dir / "Hack"
        ZIO.attemptBlockingIO(os.write(target / "keep.ttf", "original", createFolders = true)) *>
          DirectorySwap.replace(dir / "does-not-exist", target).either.map { result =>
            eagerly(
              assertTrue(
                result.left.exists(_.isInstanceOf[SwapError.MoveInto]),
                os.read(target / "keep.ttf") == "original",
                !os.exists(dir / "Hack.old"),
              ),
            )
          }
      }
    ,
    test("a failed rename without a previous install leaves nothing behind"):
      withTempDir { dir =>
        DirectorySwap.replace(dir / "does-not-exist", dir / "Hack").either.map { result =>
          eagerly(assertTrue(result.isLeft, os.list(dir).isEmpty))
        }
      },
  )

package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.install.EagerAssertion.eagerly

import zio.Task
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The Go `ExtractFontZip` scenarios plus the streaming cases a `ZipInputStream` port adds. */
object ArchiveExtractorSuite extends ZIOSpecDefault:
  private def withTempDir[A](test: os.Path => Task[A]): Task[A] = ZIO.acquireReleaseWith(
    ZIO.attemptBlockingIO(os.temp.dir(prefix = "archive-extractor")),
  )(dir => ZIO.attemptBlockingIO(os.remove.all(dir)).orDie)(test)

  private def extract(
      dir: os.Path,
      bytes: Array[Byte],
      limits: SizeLimits = SizeLimits.default,
  ): Task[(os.Path, os.Path, Either[ArchiveError, ExtractedCount])] = ZIO
    .attemptBlockingIO:
      val zip = FontZips.write(dir, "font.zip", bytes)
      val out = dir / "out"
      os.makeDir(out)
      (zip, out)
    .flatMap((zip, out) =>
      ArchiveExtractor.extract(zip, out, limits).either.map(result => (zip, out, result)),
    )

  override def spec: Spec[TestEnvironment, Any] = suite("ArchiveExtractor")(
    test("extracts only font files, flattened to their base name"):
      withTempDir { dir =>
        extract(
          dir,
          FontZips.deflated("Font.ttf" -> "font", "nested/Font.otf" -> "font", "README.md" -> "docs"),
        )
          .map { case (_, out, result) =>
            eagerly(
              assertTrue(
                result.map(_.value) == Right(2),
                os.list(out).map(_.last).sorted == Seq("Font.otf", "Font.ttf"),
                os.read(out / "Font.otf") == "font",
              ),
            )
          }
      }
    ,
    test("an upper-case .TTF extension is a font file"):
      withTempDir { dir =>
        extract(dir, FontZips.deflated("Hack.TTF" -> "font")).map { case (_, out, result) =>
          eagerly(assertTrue(result.map(_.value) == Right(1), os.exists(out / "Hack.TTF")))
        }
      }
    ,
    test("directory entries are skipped even when their name ends like a font"):
      withTempDir { dir =>
        extract(dir, FontZips.deflated("fonts.ttf/" -> "", "fonts.ttf/A.ttc" -> "font")).map {
          case (_, out, result) =>
            eagerly(assertTrue(result.map(_.value) == Right(1), os.list(out).map(_.last) == Seq("A.ttc")))
        }
      }
    ,
    test("an archive without font files is NoFontFiles"):
      withTempDir { dir =>
        extract(dir, FontZips.noFonts).map { case (zip, _, result) =>
          eagerly(
            assertTrue(
              result == Left(ArchiveError.NoFontFiles(zip)),
              result.left.map(_.render) == Left(s"extract $zip: no font files found"),
            ),
          )
        }
      }
    ,
    test("a symlink in place of the archive is refused rather than followed"):
      withTempDir { dir =>
        ZIO
          .attemptBlockingIO {
            val target = FontZips.write(dir, "real.zip", FontZips.family("Hack"))
            val link   = dir / "font.zip"
            os.symlink(link, target)
            val out    = dir / "out"
            os.makeDir(out)
            (link, out)
          }
          .flatMap { (link, out) =>
            ArchiveExtractor.extract(link, out, SizeLimits.default).either.map { result =>
              eagerly(assertTrue(result.left.exists(_.isInstanceOf[ArchiveError.Open]), os.list(out).isEmpty))
            }
          }
      }
    ,
    test("a missing archive is an Open error"):
      withTempDir { dir =>
        val missing = dir / "absent.zip"
        ArchiveExtractor.extract(missing, dir, SizeLimits.default).either.map { result =>
          eagerly(
            assertTrue(
              result.left.exists(_.isInstanceOf[ArchiveError.Open]),
              result.left.map(_.render).left.exists(_.startsWith(s"open font zip $missing: ")),
            ),
          )
        }
      }
    ,
    test("a file that is not a zip has no entries and therefore no font files"):
      withTempDir { dir =>
        ZIO
          .attemptBlockingIO {
            val zip = dir / "font.zip"
            os.write(zip, "not a zip")
            zip
          }
          .flatMap(zip =>
            ArchiveExtractor.extract(zip, dir, SizeLimits.default).either.map(result => (zip, result)),
          )
          .map { case (zip, result) => eagerly(assertTrue(result == Left(ArchiveError.NoFontFiles(zip)))) }
      }
    ,
    test("an entry that declares more than the font file cap is refused before extraction"):
      withTempDir { dir =>
        val limits = SizeLimits(fontFile = ByteLimit.bytes(8))
        extract(dir, FontZips.stored("Big.ttf" -> "this font is larger than the cap"), limits).map {
          case (zip, out, result) => eagerly(
              assertTrue(
                result == Left(ArchiveError.EntryTooLarge(zip, "Big.ttf", 32L, ByteLimit.bytes(8))),
                os.list(out).isEmpty,
              ),
            )
        }
      }
    ,
    test("an entry whose stream runs past the cap is refused even without a declared size"):
      withTempDir { dir =>
        val limits = SizeLimits(fontFile = ByteLimit.bytes(8))
        extract(dir, FontZips.deflated("Big.ttf" -> "this font is larger than the cap"), limits).map {
          case (_, _, result) => eagerly(
              assertTrue(
                result == Left(
                  ArchiveError.Entry("Big.ttf", ArchiveEntryError.Oversize("Big.ttf", ByteLimit.bytes(8))),
                ),
                result.left.map(_.render) == Left("extract Big.ttf: font file Big.ttf exceeds 8 byte limit"),
              ),
            )
        }
      }
    ,
    test("an entry name carrying a raw escape sequence is neutralised in the rendered error"):
      withTempDir { dir =>
        val hostile = "Big\u001b[2J.ttf"
        val limits  = SizeLimits(fontFile = ByteLimit.bytes(8))
        extract(dir, FontZips.stored(hostile -> "this font is larger than the cap"), limits).map {
          case (zip, _, result) =>
            val rendered = result.left.toOption.map(_.render).getOrElse("")
            eagerly(
              assertTrue(
                result == Left(ArchiveError.EntryTooLarge(zip, hostile, 32L, ByteLimit.bytes(8))),
                !rendered.contains("\u001b"),
                rendered.contains("Big?[2J.ttf"),
              ),
            )
        }
      }
    ,
    test("an entry of exactly the cap is accepted"):
      withTempDir { dir =>
        val limits = SizeLimits(fontFile = ByteLimit.bytes(4))
        extract(dir, FontZips.deflated("Ok.ttf" -> "font"), limits).map { case (_, _, result) =>
          eagerly(assertTrue(result.map(_.value) == Right(1)))
        }
      }
    ,
    test("declared sizes that add up past the archive cap are refused"):
      withTempDir { dir =>
        val limits = SizeLimits(fontFile = ByteLimit.mebibytes(1), archive = ByteLimit.bytes(12))
        extract(dir, FontZips.stored("A.ttf" -> "ten bytes!", "B.ttf" -> "ten bytes!"), limits).map {
          case (zip, _, result) =>
            eagerly(assertTrue(result == Left(ArchiveError.ArchiveTooLarge(zip, ByteLimit.bytes(12)))))
        }
      }
    ,
    test("streamed sizes that add up past the archive cap are refused when nothing is declared"):
      withTempDir { dir =>
        val limits = SizeLimits(fontFile = ByteLimit.mebibytes(1), archive = ByteLimit.bytes(12))
        extract(dir, FontZips.deflated("A.ttf" -> "ten bytes!", "B.ttf" -> "ten bytes!"), limits).map {
          case (zip, _, result) =>
            eagerly(assertTrue(result == Left(ArchiveError.ArchiveTooLarge(zip, ByteLimit.bytes(12)))))
        }
      }
    ,
    test("a font whose base name cannot be a file name (a NUL byte) is an entry error"):
      withTempDir { dir =>
        extract(dir, FontZips.deflated("bad/nul\u0000.ttf" -> "font")).map { case (_, _, result) =>
          eagerly(assertTrue(result match
            case Left(ArchiveError.Entry(_, _: ArchiveEntryError.InvalidName)) => true
            case _                                                             => false))
        }
      },
  )

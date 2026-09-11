package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.http.ByteLimit

/** The Go `ExtractFontZip` scenarios plus the streaming cases a `ZipInputStream` port adds. */
final class ArchiveExtractorSuite extends munit.FunSuite:
  private val tempDir = FunFixture[os.Path](_ => os.temp.dir(prefix = "archive-extractor"), os.remove.all(_))

  private def extract(
      dir: os.Path,
      bytes: Array[Byte],
      limits: SizeLimits = SizeLimits.default,
  ): (os.Path, os.Path, Either[ArchiveError, ExtractedCount]) =
    val zip = FontZips.write(dir, "font.zip", bytes)
    val out = dir / "out"
    os.makeDir(out)
    (zip, out, ArchiveExtractor.extract(zip, out, limits))

  tempDir.test("extracts only font files, flattened to their base name"): dir =>
    val (_, out, result) = extract(
      dir,
      FontZips.deflated("Font.ttf" -> "font", "nested/Font.otf" -> "font", "README.md" -> "docs"),
    )
    assertEquals(result.map(_.value), Right(2))
    assertEquals(os.list(out).map(_.last).sorted, Seq("Font.otf", "Font.ttf"))
    assertEquals(os.read(out / "Font.otf"), "font")

  tempDir.test("an upper-case .TTF extension is a font file"): dir =>
    val (_, out, result) = extract(dir, FontZips.deflated("Hack.TTF" -> "font"))
    assertEquals(result.map(_.value), Right(1))
    assert(os.exists(out / "Hack.TTF"))

  tempDir.test("directory entries are skipped even when their name ends like a font"): dir =>
    val (_, out, result) = extract(dir, FontZips.deflated("fonts.ttf/" -> "", "fonts.ttf/A.ttc" -> "font"))
    assertEquals(result.map(_.value), Right(1))
    assertEquals(os.list(out).map(_.last), Seq("A.ttc"))

  tempDir.test("an archive without font files is NoFontFiles"): dir =>
    val (zip, _, result) = extract(dir, FontZips.noFonts)
    assertEquals(result, Left(ArchiveError.NoFontFiles(zip)))
    assertEquals(result.left.map(_.render), Left(s"extract $zip: no font files found"))

  tempDir.test("a symlink in place of the archive is refused rather than followed"): dir =>
    val target = FontZips.write(dir, "real.zip", FontZips.family("Hack"))
    val link   = dir / "font.zip"
    os.symlink(link, target)
    val out    = dir / "out"
    os.makeDir(out)
    val result = ArchiveExtractor.extract(link, out, SizeLimits.default)
    assert(result.left.exists(_.isInstanceOf[ArchiveError.Open]), result.toString)
    assertEquals(os.list(out), Seq.empty)

  tempDir.test("a missing archive is an Open error"): dir =>
    val missing = dir / "absent.zip"
    val result  = ArchiveExtractor.extract(missing, dir, SizeLimits.default)
    assert(result.left.exists(_.isInstanceOf[ArchiveError.Open]), result.toString)
    assert(result.left.map(_.render).left.exists(_.startsWith(s"open font zip $missing: ")))

  tempDir.test("a file that is not a zip has no entries and therefore no font files"): dir =>
    val zip = dir / "font.zip"
    os.write(zip, "not a zip")
    assertEquals(ArchiveExtractor.extract(zip, dir, SizeLimits.default), Left(ArchiveError.NoFontFiles(zip)))

  tempDir.test("an entry that declares more than the font file cap is refused before extraction"): dir =>
    val limits             = SizeLimits(fontFile = ByteLimit.bytes(8))
    val (zip, out, result) =
      extract(dir, FontZips.stored("Big.ttf" -> "this font is larger than the cap"), limits)
    assertEquals(result, Left(ArchiveError.EntryTooLarge(zip, "Big.ttf", 32L, ByteLimit.bytes(8))))
    assertEquals(os.list(out), Seq.empty)

  tempDir.test("an entry whose stream runs past the cap is refused even without a declared size"): dir =>
    val limits         = SizeLimits(fontFile = ByteLimit.bytes(8))
    val (_, _, result) =
      extract(dir, FontZips.deflated("Big.ttf" -> "this font is larger than the cap"), limits)
    assertEquals(
      result,
      Left(ArchiveError.Entry("Big.ttf", ArchiveEntryError.Oversize("Big.ttf", ByteLimit.bytes(8)))),
    )
    assertEquals(result.left.map(_.render), Left("extract Big.ttf: font file Big.ttf exceeds 8 byte limit"))

  tempDir.test("an entry of exactly the cap is accepted"): dir =>
    val limits         = SizeLimits(fontFile = ByteLimit.bytes(4))
    val (_, _, result) = extract(dir, FontZips.deflated("Ok.ttf" -> "font"), limits)
    assertEquals(result.map(_.value), Right(1))

  tempDir.test("declared sizes that add up past the archive cap are refused"): dir =>
    val limits           = SizeLimits(fontFile = ByteLimit.mebibytes(1), archive = ByteLimit.bytes(12))
    val (zip, _, result) =
      extract(dir, FontZips.stored("A.ttf" -> "ten bytes!", "B.ttf" -> "ten bytes!"), limits)
    assertEquals(result, Left(ArchiveError.ArchiveTooLarge(zip, ByteLimit.bytes(12))))

  tempDir.test("streamed sizes that add up past the archive cap are refused when nothing is declared"): dir =>
    val limits           = SizeLimits(fontFile = ByteLimit.mebibytes(1), archive = ByteLimit.bytes(12))
    val (zip, _, result) =
      extract(dir, FontZips.deflated("A.ttf" -> "ten bytes!", "B.ttf" -> "ten bytes!"), limits)
    assertEquals(result, Left(ArchiveError.ArchiveTooLarge(zip, ByteLimit.bytes(12))))

  tempDir.test("a font whose base name cannot be a file name (a NUL byte) is an entry error"): dir =>
    val (_, _, result) = extract(dir, FontZips.deflated("bad/nul\u0000.ttf" -> "font"))
    assert(
      result.left.exists {
        case ArchiveError.Entry(_, _: ArchiveEntryError.InvalidName) => true
        case _                                                       => false
      },
      result.toString,
    )

package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.process.ExitStatus
import io.worxbend.nerdfonts.process.ProcessError
import io.worxbend.nerdfonts.releases.ReleaseUrls
import io.worxbend.nerdfonts.releases.Sha256Digest

import scala.concurrent.duration.DurationInt

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The message table of SPEC §6.3, one row per test. */
object InstallErrorSuite extends ZIOSpecDefault:
  private def family(name: String): FamilyName = FamilyName.parse(name) match
    case Right(value) => value
    case Left(error)  => throw new AssertionError(error.render)

  private def digest(hex: String): Sha256Digest = Sha256Digest.parse(hex) match
    case Some(value) => value
    case None        => throw new AssertionError(s"bad digest $hex")

  private val hack    = family("Hack")
  private val url     = ReleaseUrls.github.download(ReleaseSelector.Latest, hack)
  private val root    = os.Path("/fonts")
  private val zip     = os.Path("/tmp/nerd-font-123.zip")
  private val staging = root / ".Hack-456"
  private val target  = root / "Hack"
  private val limit   = ByteLimit.bytes(10)

  private def render(cause: FamilyInstallError): String = InstallError.Family(hack, cause).render

  def spec = suite("InstallError")(
    test("a destination failure"):
      assertTrue(
        InstallError
          .Destination(root, "permission denied")
          .render == "create destination /fonts: permission denied",
      )
    ,
    test("a family failure names the family before the cause"):
      assertTrue(
        render(FamilyInstallError.Download(url, HttpError.Status(404))) ==
          "install Nerd Font family Hack: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip: 404 Not Found",
      )
    ,
    test("a transport failure renders its cause"):
      assertTrue(
        render(FamilyInstallError.Download(url, HttpError.Transport("connection reset"))) ==
          "install Nerd Font family Hack: download https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip: connection reset",
      )
    ,
    test("an oversize body renders the bare limit and a declared length renders the size"):
      assertTrue(
        FamilyInstallError.Download(url, HttpError.TooLarge(limit, None)).render(hack) ==
          s"download ${url.value}: exceeds 10 byte limit",
        FamilyInstallError.Download(url, HttpError.TooLarge(limit, Some(99L))).render(hack) ==
          s"download ${url.value}: size 99 bytes exceeds 10 byte limit",
      )
    ,
    test("a temp zip failure"):
      assertTrue(
        FamilyInstallError.TempZip("read-only").render(hack) == "create temporary zip file: read-only",
      )
    ,
    test("a copy failure"):
      assertTrue(
        FamilyInstallError.Copy(url, zip, "disk full").render(hack) ==
          s"copy download ${url.value} to /tmp/nerd-font-123.zip: disk full",
      )
    ,
    test("a checksum mismatch names the family a second time"):
      val got  = digest("a" * 64)
      val want = digest("b" * 64)
      assertTrue(
        render(FamilyInstallError.ChecksumMismatch(got, want)) ==
          s"install Nerd Font family Hack: checksum mismatch for Hack: downloaded sha256 ${"a" * 64}, expected ${"b" * 64}",
      )
    ,
    test("a staging failure"):
      assertTrue(
        FamilyInstallError.Staging(root, "read-only").render(hack) ==
          "create temporary family destination in /fonts: read-only",
      )
    ,
    test("every archive error is wrapped with the zip and staging paths"):
      val errors = Vector(
        ArchiveError.Open(zip, "bad header")                                        -> "open font zip /tmp/nerd-font-123.zip: bad header",
        ArchiveError.NoFontFiles(zip)                                               -> "extract /tmp/nerd-font-123.zip: no font files found",
        ArchiveError.EntryTooLarge(zip, "Big.ttf", 99L, limit)                      ->
          "extract /tmp/nerd-font-123.zip: font file Big.ttf declares 99 bytes, exceeds 10 byte limit",
        ArchiveError.ArchiveTooLarge(zip, limit)                                    ->
          "extract /tmp/nerd-font-123.zip: total uncompressed size exceeds 10 byte limit",
        ArchiveError.Entry("Big.ttf", ArchiveEntryError.Oversize("Big.ttf", limit)) ->
          "extract Big.ttf: font file Big.ttf exceeds 10 byte limit",
      )
      assertTrue(
        errors.forall: (error, expected) =>
          FamilyInstallError.Extraction(zip, staging, error).render(hack) ==
            s"extract /tmp/nerd-font-123.zip to /fonts/.Hack-456: $expected",
      )
    ,
    test("every entry error renders its step"):
      val file = target / "Hack.ttf"
      assertTrue(
        ArchiveEntryError.InvalidName("x.ttf", "nul byte").render == "invalid font file name x.ttf: nul byte",
        ArchiveEntryError.Create(file, "denied").render == "create font file /fonts/Hack/Hack.ttf: denied",
        ArchiveEntryError.Copy("Hack.ttf", file, "short").render ==
          "copy font file Hack.ttf to /fonts/Hack/Hack.ttf: short",
        ArchiveEntryError.Flush(file, "EIO").render == "flush font file /fonts/Hack/Hack.ttf: EIO",
        ArchiveEntryError.Finalize(file, "EIO").render == "finalize font file /fonts/Hack/Hack.ttf: EIO",
      )
    ,
    test("every swap error names both paths"):
      val backup = root / "Hack.old"
      assertTrue(
        FamilyInstallError.Swap(SwapError.RemoveBackup(backup, "busy")).render(hack) ==
          "remove old backup /fonts/Hack.old: busy",
        FamilyInstallError.Swap(SwapError.MoveAside(target, backup, "busy")).render(hack) ==
          "move existing destination /fonts/Hack to /fonts/Hack.old: busy",
        FamilyInstallError.Swap(SwapError.MoveInto(staging, target, "busy")).render(hack) ==
          "move extracted fonts /fonts/.Hack-456 to /fonts/Hack: busy",
      )
    ,
    test("the deadline renders in Go's duration wording"):
      assertTrue(
        FamilyInstallError.TimedOut(10.minutes).render(hack) == "timed out after 10 minutes",
        FontInstaller.defaultFamilyDeadline == 10.minutes,
      )
    ,
    test("a font cache failure renders the exit status or the launch error"):
      assertTrue(
        InstallError.FontCache(root, FontCacheError.Exit(ExitStatus.of(1))).render ==
          "run fc-cache for /fonts: exit status 1",
        InstallError
          .FontCache(root, FontCacheError.Launch(ProcessError.Failed("fc-cache", "denied")))
          .render ==
          "run fc-cache for /fonts: fc-cache: denied",
      ),
  )

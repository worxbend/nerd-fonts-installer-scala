package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.http.ByteLimit

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * Pins the §3.1 defaults, the security boundary AGENTS.md says must never weaken: every install test either
 * lowers a limit or relies on `SizeLimits.default` implicitly, so nothing else in the suite would notice a
 * regression here (a typo'd unit, a swapped constant).
 */
object SizeLimitsSuite extends ZIOSpecDefault:
  // The largest known Nerd Fonts archive, Noto.zip, is ~601 MB; the download cap must clear it with room to
  // spare, and the archive cap (the decompression-bomb guard) must never be tighter than the download cap.
  private val minNotoBytes = 601_124_599L

  def spec = suite("SizeLimits")(
    test("the default download cap is 768 MiB"):
      assertTrue(SizeLimits.default.download == ByteLimit.mebibytes(768))
    ,
    test("the default font file cap is 128 MiB"):
      assertTrue(SizeLimits.default.fontFile == ByteLimit.mebibytes(128))
    ,
    test("the default archive cap is 2 GiB"):
      assertTrue(SizeLimits.default.archive == ByteLimit.gibibytes(2))
    ,
    test("the default manifest cap is 1 MiB"):
      assertTrue(SizeLimits.default.manifest == ByteLimit.mebibytes(1))
    ,
    test("the default API page cap is 8 MiB"):
      assertTrue(SizeLimits.default.apiPage == ByteLimit.mebibytes(8))
    ,
    test("the default download cap admits the largest known Nerd Fonts archive (Noto.zip)"):
      assertTrue(SizeLimits.default.download.value >= minNotoBytes)
    ,
    test("the archive cap is never tighter than the download cap"):
      assertTrue(SizeLimits.default.archive.value >= SizeLimits.default.download.value),
  )

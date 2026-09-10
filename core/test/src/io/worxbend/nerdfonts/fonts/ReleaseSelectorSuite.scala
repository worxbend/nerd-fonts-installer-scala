package io.worxbend.nerdfonts.fonts

final class ReleaseSelectorSuite extends munit.FunSuite:
  test("a blank value selects the latest release"):
    assertEquals(ReleaseSelector.parse("   "), ReleaseSelector.Latest)

  test("the latest keyword selects the latest release even when padded"):
    assertEquals(ReleaseSelector.parse(" latest "), ReleaseSelector.Latest)

  test("the keyword is case-sensitive, as in Go"):
    assertEquals(ReleaseSelector.parse("Latest").render, "Latest")

  test("anything else is a trimmed tag"):
    assertEquals(ReleaseSelector.parse(" v3.4.0 ").render, "v3.4.0")

  test("Latest renders as the keyword"):
    assertEquals(ReleaseSelector.Latest.render, "latest")

  test("a release tag is trimmed and never blank"):
    assertEquals(ReleaseTag.parse(" v1 ").map(_.value), Some("v1"))
    assertEquals(ReleaseTag.parse(" \t"), None)

  test("a destination path is trimmed and never blank"):
    assertEquals(DestinationPath.parse(" ~/x ").map(_.value), Some("~/x"))
    assertEquals(DestinationPath.parse(""), None)
    assertEquals(DestinationPath.default.value, "~/.local/share/fonts/NerdFonts")

  test("boolean flags convert to their enums at the boundary"):
    assertEquals(RefreshFontCache.fromBoolean(true), RefreshFontCache.Enabled)
    assertEquals(RefreshFontCache.fromBoolean(false), RefreshFontCache.Disabled)
    assertEquals(DryRun.fromBoolean(true), DryRun.Enabled)
    assertEquals(DryRun.fromBoolean(false), DryRun.Disabled)

package io.worxbend.nerdfonts.fonts

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object ReleaseSelectorSuite extends ZIOSpecDefault:
  def spec = suite("ReleaseSelector")(
    test("a blank value selects the latest release"):
      assertTrue(ReleaseSelector.parse("   ") == ReleaseSelector.Latest)
    ,
    test("the latest keyword selects the latest release even when padded"):
      assertTrue(ReleaseSelector.parse(" latest ") == ReleaseSelector.Latest)
    ,
    test("the keyword is case-sensitive, as in Go"):
      assertTrue(ReleaseSelector.parse("Latest").render == "Latest")
    ,
    test("anything else is a trimmed tag"):
      assertTrue(ReleaseSelector.parse(" v3.4.0 ").render == "v3.4.0")
    ,
    test("Latest renders as the keyword"):
      assertTrue(ReleaseSelector.Latest.render == "latest")
    ,
    test("of classifies an already-validated tag without re-parsing it"):
      assertTrue(
        ReleaseSelector.of(ReleaseTag.parse("latest").get) == ReleaseSelector.Latest,
        ReleaseSelector.of(ReleaseTag.parse("v3.4.0").get) ==
          ReleaseSelector.Tagged(ReleaseTag.parse("v3.4.0").get),
      )
    ,
    test("parse delegates to of once the text is trimmed and validated"):
      assertTrue(ReleaseSelector.parse(" v3.4.0 ") == ReleaseSelector.of(ReleaseTag.parse("v3.4.0").get))
    ,
    test("a release tag is trimmed and never blank"):
      assertTrue(
        ReleaseTag.parse(" v1 ").map(_.value) == Some("v1"),
        ReleaseTag.parse(" \t").isEmpty,
      )
    ,
    test("a destination path is trimmed and never blank"):
      assertTrue(
        DestinationPath.parse(" ~/x ").map(_.value) == Some("~/x"),
        DestinationPath.parse("").isEmpty,
        DestinationPath.default.value == "~/.local/share/fonts/NerdFonts",
      )
    ,
    test("boolean flags convert to their enums at the boundary"):
      assertTrue(
        RefreshFontCache.fromBoolean(true) == RefreshFontCache.Enabled,
        RefreshFontCache.fromBoolean(false) == RefreshFontCache.Disabled,
        DryRun.fromBoolean(true) == DryRun.Enabled,
        DryRun.fromBoolean(false) == DryRun.Disabled,
      ),
  )

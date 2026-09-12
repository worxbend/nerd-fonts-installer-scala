package io.worxbend.nerdfonts.fonts

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object InstallConfigSuite extends ZIOSpecDefault:
  private def validated(
      release: String = "latest",
      destination: String = "~/fonts",
      families: Vector[String] = Vector("Hack"),
  ): Either[ConfigValidationError, InstallConfig] =
    InstallConfig.validated(release, destination, RefreshFontCache.Disabled, families)

  def spec = suite("InstallConfig")(
    test("accepts a complete configuration and keeps family order"):
      val config = validated(release = "v3.4.0", families = Vector("JetBrainsMono", "Hack"))
      assertTrue(
        config.map(_.families.map(_.value)) == Right(Vector("JetBrainsMono", "Hack")),
        config.map(_.selector.render) == Right("v3.4.0"),
        config.map(_.destination.value) == Right("~/fonts"),
      )
    ,
    test("maps the latest keyword to the Latest selector"):
      assertTrue(validated(release = "latest").map(_.selector) == Right(ReleaseSelector.Latest))
    ,
    test("trims release, destination and family names before validating"):
      val config = validated(release = " v3.4.0 ", destination = "  ~/fonts  ", families = Vector(" Hack "))
      assertTrue(
        config.map(_.selector.render) == Right("v3.4.0"),
        config.map(_.destination.value) == Right("~/fonts"),
        config.map(_.families.map(_.value)) == Right(Vector("Hack")),
      )
    ,
    test("rejects a blank release with a message naming the field"):
      assertTrue(validated(release = "  ").left.map(_.render) == Left("release is required"))
    ,
    test("rejects a blank destination with a message naming the field"):
      assertTrue(validated(destination = "").left.map(_.render) == Left("destination is required"))
    ,
    test("rejects an empty family list with a message requiring at least one family"):
      assertTrue(
        validated(families = Vector.empty).left.map(_.render) == Left("at least one font family is required"),
      )
    ,
    test("rejects an unsafe family with the family-name message"):
      assertTrue(
        validated(families = Vector("Hack", "../x")).left.map(_.render) ==
          Left("unsafe font family name \"../x\""),
      )
    ,
    test("rejects a blank family entry as empty"):
      assertTrue(
        validated(families = Vector("  ")).left.map(_.render) == Left("font family names cannot be empty"),
      )
    ,
    test("rejects a duplicate family with a message, comparing after trimming"):
      assertTrue(
        validated(families = Vector("Hack", " Hack ")).left.map(_.render) ==
          Left("duplicate font family \"Hack\""),
      )
    ,
    test("reports the release error before the destination error"):
      assertTrue(validated(release = "", destination = "") == Left(ConfigValidationError.ReleaseRequired))
    ,
    test("reports family errors in list order, so a duplicate before an unsafe name wins"):
      assertTrue(
        validated(families = Vector("Hack", "Hack", "../x")).left.map(_.render) ==
          Left("duplicate font family \"Hack\""),
      )
    ,
    test("carries the refresh flag through unchanged"):
      val config = InstallConfig.validated("latest", "~/fonts", RefreshFontCache.Enabled, Vector("Hack"))
      assertTrue(config.map(_.refreshFontCache) == Right(RefreshFontCache.Enabled)),
  )

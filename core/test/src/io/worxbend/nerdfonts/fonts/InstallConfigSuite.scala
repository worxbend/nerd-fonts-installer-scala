package io.worxbend.nerdfonts.fonts

final class InstallConfigSuite extends munit.FunSuite:
  private def validated(
      release: String = "latest",
      destination: String = "~/fonts",
      families: Vector[String] = Vector("Hack"),
  ): Either[ConfigValidationError, InstallConfig] =
    InstallConfig.validated(release, destination, RefreshFontCache.Disabled, families)

  test("accepts a complete configuration and keeps family order"):
    val config = validated(release = "v3.4.0", families = Vector("JetBrainsMono", "Hack"))
    assertEquals(config.map(_.families.map(_.value)), Right(Vector("JetBrainsMono", "Hack")))
    assertEquals(config.map(_.selector.render), Right("v3.4.0"))
    assertEquals(config.map(_.destination.value), Right("~/fonts"))

  test("maps the latest keyword to the Latest selector"):
    assertEquals(validated(release = "latest").map(_.selector), Right(ReleaseSelector.Latest))

  test("trims release, destination and family names before validating"):
    val config = validated(release = " v3.4.0 ", destination = "  ~/fonts  ", families = Vector(" Hack "))
    assertEquals(config.map(_.selector.render), Right("v3.4.0"))
    assertEquals(config.map(_.destination.value), Right("~/fonts"))
    assertEquals(config.map(_.families.map(_.value)), Right(Vector("Hack")))

  test("rejects a blank release with the Go message"):
    assertEquals(validated(release = "  ").left.map(_.render), Left("release is required"))

  test("rejects a blank destination with the Go message"):
    assertEquals(validated(destination = "").left.map(_.render), Left("destination is required"))

  test("rejects an empty family list with the Go message"):
    assertEquals(
      validated(families = Vector.empty).left.map(_.render),
      Left("at least one font family is required"),
    )

  test("rejects an unsafe family with the family-name message"):
    assertEquals(
      validated(families = Vector("Hack", "../x")).left.map(_.render),
      Left("unsafe font family name \"../x\""),
    )

  test("rejects a blank family entry as empty"):
    assertEquals(
      validated(families = Vector("  ")).left.map(_.render),
      Left("font family names cannot be empty"),
    )

  test("rejects a duplicate family with the Go message, comparing after trimming"):
    assertEquals(
      validated(families = Vector("Hack", " Hack ")).left.map(_.render),
      Left("duplicate font family \"Hack\""),
    )

  test("reports the release error before the destination error"):
    assertEquals(validated(release = "", destination = ""), Left(ConfigValidationError.ReleaseRequired))

  test("reports family errors in list order, so a duplicate before an unsafe name wins"):
    assertEquals(
      validated(families = Vector("Hack", "Hack", "../x")).left.map(_.render),
      Left("duplicate font family \"Hack\""),
    )

  test("carries the refresh flag through unchanged"):
    val config = InstallConfig.validated("latest", "~/fonts", RefreshFontCache.Enabled, Vector("Hack"))
    assertEquals(config.map(_.refreshFontCache), Right(RefreshFontCache.Enabled))

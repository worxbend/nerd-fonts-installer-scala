package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.RefreshFontCache

final class JsonConfigDecoderSuite extends munit.FunSuite:
  private val path = os.Path("/etc/fonts.json")

  private def decode(text: String): Either[ConfigError, ConfigDocument] = JsonConfigDecoder.decode(path, text)

  test("a number in the family list is a wrong type, never coerced"):
    assertEquals(
      decode("""{"families": [3270]}"""),
      Left(ConfigError.WrongType(path, "families", "list of strings")),
    )

  test("a number as the release is a wrong type"):
    assertEquals(decode("""{"release": 1.0}"""), Left(ConfigError.WrongType(path, "release", "string")))

  test("a boolean as the destination is a wrong type"):
    assertEquals(
      decode("""{"destination": true}"""),
      Left(ConfigError.WrongType(path, "destination", "string")),
    )

  test("the string \"true\" for refresh_font_cache is a wrong type"):
    assertEquals(
      decode("""{"refresh_font_cache": "true"}"""),
      Left(ConfigError.WrongType(path, "refresh_font_cache", "boolean")),
    )

  test("a JSON boolean for refresh_font_cache is accepted"):
    assertEquals(
      decode("""{"refresh_font_cache": false}""").map(_.refreshFontCache),
      Right(Some(RefreshFontCache.Disabled)),
    )

  test("null for release leaves the key unset"):
    assertEquals(decode("""{"release": null}""").map(_.release), Right(None))

  test("null for families is an empty list"):
    assertEquals(decode("""{"families": null}""").map(_.families), Right(None))

  test("null inside families is the empty string, which validation rejects as an empty name"):
    val document = decode("""{"families": [null, "Hack"]}""")
    assertEquals(document.map(_.families), Right(Some(Vector("", "Hack"))))
    assertEquals(
      document.flatMap(_.validated.left.map(ConfigError.Invalid(path, _))).left.map(_.render),
      Left("font family names cannot be empty"),
    )

  test("an object where families expects an array is a wrong type"):
    assertEquals(
      decode("""{"families": {"a": "Hack"}}"""),
      Left(ConfigError.WrongType(path, "families", "list of strings")),
    )

  test("a second top-level value is reported as multiple json values"):
    assertEquals(
      decode("""{"families": ["Hack"]} {}""").left.map(_.render),
      Left(s"parse $path: multiple json values"),
    )

  test("trailing whitespace after the value is fine"):
    assertEquals(decode("{\"families\": [\"Hack\"]}\n\n").map(_.families), Right(Some(Vector("Hack"))))

  test("an unknown field is rejected"):
    assertEquals(
      decode("""{"families": ["Hack"], "font_family": "Hack"}"""),
      Left(ConfigError.UnknownField(path, "font_family")),
    )

  test("a top-level array is a parse error"):
    assert(decode("""["Hack"]""").left.exists {
      case ConfigError.Parse(_, message) => message.contains("mapping")
      case _                             => false
    })

  test("a top-level null is an empty document"):
    assertEquals(decode("null"), Right(ConfigDocument.empty))

  test("an empty file is a parse error, as Go reports EOF"):
    assert(decode("").left.exists {
      case ConfigError.Parse(_, _) => true
      case _                       => false
    })

  test("a truncated document is a parse error with the parse prefix"):
    assert(decode("""{"families": [""").left.map(_.render).left.exists(_.startsWith(s"parse $path: ")))

  test("a repeated key keeps the last value like encoding/json"):
    assertEquals(decode("""{"release": "a", "release": "b"}""").map(_.release), Right(Some("b")))

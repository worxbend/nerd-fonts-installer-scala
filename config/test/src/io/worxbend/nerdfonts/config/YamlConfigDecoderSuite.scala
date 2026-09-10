package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.RefreshFontCache

final class YamlConfigDecoderSuite extends munit.FunSuite:
  private val path = os.Path("/etc/fonts.yaml")

  private def decode(text: String): Either[ConfigError, ConfigDocument] = YamlConfigDecoder.decode(path, text)

  test("an integer scalar in the family list is its literal text"):
    assertEquals(decode("families: [3270]").map(_.families), Right(Some(Vector("3270"))))

  test("a float scalar as the release is its literal text"):
    assertEquals(decode("release: 1.0").map(_.release), Right(Some("1.0")))

  test("a boolean-looking scalar as the destination is its literal text"):
    assertEquals(decode("destination: no").map(_.destination), Right(Some("no")))

  Vector("yes", "\"yes\"", "on", "True", "'Y'").foreach: literal =>
    test(s"refresh_font_cache: $literal is enabled"):
      assertEquals(
        decode(s"refresh_font_cache: $literal").map(_.refreshFontCache),
        Right(Some(RefreshFontCache.Enabled)),
      )

  Vector("no", "'off'", "FALSE", "n").foreach: literal =>
    test(s"refresh_font_cache: $literal is disabled"):
      assertEquals(
        decode(s"refresh_font_cache: $literal").map(_.refreshFontCache),
        Right(Some(RefreshFontCache.Disabled)),
      )

  test("refresh_font_cache: 1 is a wrong type, not a boolean"):
    assertEquals(
      decode("refresh_font_cache: 1"),
      Left(ConfigError.WrongType(path, "refresh_font_cache", "boolean")),
    )
    assertEquals(
      ConfigError.WrongType(path, "refresh_font_cache", "boolean").render,
      s"parse $path: field \"refresh_font_cache\" must be a boolean",
    )

  test("a null refresh_font_cache leaves the key unset"):
    assertEquals(decode("refresh_font_cache: ~").map(_.refreshFontCache), Right(None))

  test("a null entry in the family list is dropped"):
    assertEquals(decode("families: [~, Hack]").map(_.families), Right(Some(Vector("Hack"))))

  test("a null family list is an empty list"):
    assertEquals(decode("families: ~").map(_.families), Right(None))
    assertEquals(
      decode("families: ~").flatMap(_.validated.left.map(ConfigError.Invalid(path, _))).left.map(_.render),
      Left("at least one font family is required"),
    )

  test("a null release leaves the key unset so the default applies"):
    assertEquals(decode("release: ~\nfamilies: [Hack]").map(_.release), Right(None))
    assertEquals(decode("release:\nfamilies: [Hack]").map(_.release), Right(None))

  test("a quoted empty release is the empty string, not an unset key"):
    assertEquals(decode("release: ''").map(_.release), Right(Some("")))

  test("a whitespace-only document is empty"):
    assertEquals(decode("  \n\n"), Right(ConfigDocument.empty))

  test("a document that is only a null scalar is empty"):
    assertEquals(decode("~"), Right(ConfigDocument.empty))

  test("a sequence at the top level is a parse error"):
    assert(decode("- Hack\n").left.exists {
      case ConfigError.Parse(_, message) => message.contains("mapping")
      case _                             => false
    })

  test("a sequence where release expects a scalar is a wrong type"):
    assertEquals(decode("release: [v3]"), Left(ConfigError.WrongType(path, "release", "string")))

  test("a mapping where destination expects a scalar is a wrong type"):
    assertEquals(decode("destination: {a: b}"), Left(ConfigError.WrongType(path, "destination", "string")))

  test("a scalar where families expects a sequence is a wrong type"):
    assertEquals(decode("families: Hack"), Left(ConfigError.WrongType(path, "families", "list of strings")))

  test("a mapping inside the family list is a wrong type"):
    assertEquals(
      decode("families: [{name: Hack}]"),
      Left(ConfigError.WrongType(path, "families", "list of strings")),
    )

  test("a repeated key is a parse error, as in yaml.v3"):
    assertEquals(
      decode("families: [Hack]\nfamilies: [Other]\n").left.map(_.render),
      Left(s"parse $path: mapping key \"families\" already defined"),
    )

  test("the first unknown key in document order is reported"):
    assertEquals(
      decode("families: [Hack]\nzeta: 1\nalpha: 2\n"),
      Left(ConfigError.UnknownField(path, "zeta")),
    )

  test("only the first document of a multi-document stream is read"):
    assertEquals(decode("release: a\n---\nrelease: b\n").map(_.release), Right(Some("a")))

package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector

final class ConfigLoaderSuite extends munit.FunSuite:
  private val tempDir = FunFixture[os.Path](_ => os.temp.dir(prefix = "config-loader"), os.remove.all(_))

  private def load(dir: os.Path, name: String, text: String): Either[ConfigError, InstallConfig] =
    ConfigLoader.load(ConfigFiles.write(dir, name, text))

  private def families(result: Either[ConfigError, InstallConfig]): Either[String, Vector[String]] =
    result.map(_.families.map(_.value)).left.map(_.render)

  tempDir.test("applies the Go defaults when release and destination are absent"): dir =>
    val config = load(dir, "fonts.yaml", "families: [JetBrainsMono]\n")
    assertEquals(config.map(_.selector), Right(ReleaseSelector.Latest))
    assertEquals(config.map(_.destination.value), Right("~/.local/share/fonts/NerdFonts"))
    assertEquals(config.map(_.refreshFontCache), Right(RefreshFontCache.Disabled))

  tempDir.test("an explicit empty string also takes the default, as Go's ApplyDefaults does"): dir =>
    val config = load(dir, "fonts.yaml", "release: ''\ndestination: \"\"\nfamilies: [Hack]\n")
    assertEquals(config.map(_.selector), Right(ReleaseSelector.Latest))
    assertEquals(config.map(_.destination.value), Right("~/.local/share/fonts/NerdFonts"))

  tempDir.test("trims release, destination and every family"): dir =>
    val text   = "release: ' v3.4.0 '\ndestination: ' /tmp/fonts '\nfamilies: [' Hack ', ' JetBrainsMono ']\n"
    val config = load(dir, "fonts.yaml", text)
    assertEquals(config.map(_.selector.render), Right("v3.4.0"))
    assertEquals(config.map(_.destination.value), Right("/tmp/fonts"))
    assertEquals(families(config), Right(Vector("Hack", "JetBrainsMono")))

  tempDir.test("rejects an unknown field by name"): dir =>
    val result = load(dir, "fonts.yaml", "families: [Hack]\nfont_family: Hack\n")
    assertEquals(
      result.left.map(_.render),
      Left(s"parse ${dir / "fonts.yaml"}: unknown field \"font_family\""),
    )

  tempDir.test("parses a JSON config completely"): dir =>
    val text   =
      """{"release":"v3.4.0","destination":"/tmp/fonts","refresh_font_cache":true,"families":["Hack"]}"""
    val config = load(dir, "fonts.json", text)
    assertEquals(config.map(_.selector.render), Right("v3.4.0"))
    assertEquals(config.map(_.destination.value), Right("/tmp/fonts"))
    assertEquals(config.map(_.refreshFontCache), Right(RefreshFontCache.Enabled))
    assertEquals(families(config), Right(Vector("Hack")))

  tempDir.test("rejects a release that is blank after trimming"): dir =>
    val result = load(dir, "fonts.yaml", "release: '   '\ndestination: /tmp/fonts\nfamilies: [Hack]\n")
    assertEquals(result.left.map(_.render), Left("release is required"))

  tempDir.test("rejects a destination that is blank after trimming"): dir =>
    val result = load(dir, "fonts.yaml", "release: latest\ndestination: '   '\nfamilies: [Hack]\n")
    assertEquals(result.left.map(_.render), Left("destination is required"))

  tempDir.test("rejects a family that is blank after trimming"): dir =>
    val result = load(dir, "fonts.yaml", "release: latest\ndestination: /tmp/fonts\nfamilies: ['   ']\n")
    assertEquals(result.left.map(_.render), Left("font family names cannot be empty"))

  tempDir.test("rejects an empty family list"): dir =>
    val result = load(dir, "fonts.yaml", "families: []\n")
    assertEquals(result.left.map(_.render), Left("at least one font family is required"))

  tempDir.test("rejects duplicate families"): dir =>
    val result = load(dir, "fonts.yaml", "families: [Hack, Hack]\n")
    assertEquals(result.left.map(_.render), Left("duplicate font family \"Hack\""))

  tempDir.test("rejects duplicate families that only differ by surrounding whitespace"): dir =>
    val result = load(dir, "fonts.yaml", "families: [Hack, ' Hack ']\n")
    assertEquals(result.left.map(_.render), Left("duplicate font family \"Hack\""))

  Vector(
    "slash"     -> "Hack/Regular",
    "backslash" -> "Hack\\Regular",
    "absolute"  -> "/tmp/Hack",
    "dot"       -> ".",
    "dot dot"   -> "..",
  ).foreach: (label, family) =>
    tempDir.test(s"rejects an unsafe family name ($label) with the family-name message"): dir =>
      val result = load(dir, "fonts.yaml", s"families: ['$family']\n")
      assert(
        result.left.exists {
          case ConfigError.Invalid(_, ConfigValidationError.InvalidFamily(_)) => true
          case _                                                              => false
        },
        clue = result,
      )

  tempDir.test("a validation failure carries the path but renders only the Go message"): dir =>
    val result = load(dir, "fonts.yaml", "families: []\n")
    assertEquals(result.left.map(_.render), Left("at least one font family is required"))
    assert(result.left.exists {
      case ConfigError.Invalid(path, _) => path == dir / "fonts.yaml"
      case _                            => false
    })

  tempDir.test("a missing file is NotFound and renders Go's open error"): dir =>
    val path = dir / "missing.yaml"
    assertEquals(ConfigLoader.load(path), Left(ConfigError.NotFound(path)))
    assertEquals(ConfigError.NotFound(path).render, s"open $path: no such file or directory")

  tempDir.test("a path that exists but cannot be read as a file is Unreadable, not NotFound"): dir =>
    val directory = dir / "fonts.yaml"
    os.makeDir(directory)
    assert(ConfigLoader.load(directory).left.exists {
      case ConfigError.Unreadable(path, _) => path == directory
      case _                               => false
    })

  Vector("fonts.conf", "fonts.yml", "fonts", "fonts.json.bak", "fonts.YAML").foreach: name =>
    tempDir.test(s"decodes $name as YAML"): dir =>
      assertEquals(families(load(dir, name, "families: [Hack]\n")), Right(Vector("Hack")))

  Vector("fonts.json", "fonts.JSON", ".json").foreach: name =>
    tempDir.test(s"decodes $name as JSON"): dir =>
      assertEquals(families(load(dir, name, """{"families":["Hack"]}""")), Right(Vector("Hack")))

  tempDir.test("a JSON-shaped body under a YAML extension is decoded as YAML, so flow syntax still works"):
    dir =>
      assertEquals(families(load(dir, "fonts.yaml", """{"families": ["Hack"]}""")), Right(Vector("Hack")))

  tempDir.test("an empty YAML file is a document with every key absent"): dir =>
    val result = load(dir, "fonts.yaml", "")
    assertEquals(result.left.map(_.render), Left("at least one font family is required"))

  tempDir.test("a YAML syntax error renders with the parse prefix on one line"): dir =>
    val result = load(dir, "fonts.yaml", "release: [\n")
    assert(
      result.left.exists {
        case ConfigError.Parse(path, message) => path == dir / "fonts.yaml" && !message.contains("\n")
        case _                                => false
      },
      clue = result,
    )
    assert(result.left.map(_.render).left.exists(_.startsWith(s"parse ${dir / "fonts.yaml"}: ")))

  test("the shipped config.example.yaml loads with the values it documents"):
    val repoRoot = sys.props.get("nerdfonts.repoRoot").map(os.Path(_))
    val example  = repoRoot.map(_ / "config.example.yaml").getOrElse(fail("nerdfonts.repoRoot is not set"))
    val config   = ConfigLoader.load(example)
    assertEquals(config.map(_.selector), Right(ReleaseSelector.Latest))
    assertEquals(config.map(_.destination.value), Right("~/.local/share/fonts/NerdFonts"))
    assertEquals(config.map(_.refreshFontCache), Right(RefreshFontCache.Enabled))
    assertEquals(families(config), Right(Vector("JetBrainsMono", "Hack", "FiraCode", "Meslo")))

package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector

import zio.IO
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The §4 load rules over the three zio-config providers: defaults, validation, format mapping and parsing. */
object ConfigLoaderSuite extends ZIOSpecDefault:
  private def load(dir: os.Path, name: String, text: String): IO[ConfigError, InstallConfig] =
    ZIO.attemptBlockingIO(ConfigFiles.write(dir, name, text)).orDie.flatMap(ConfigLoader.load)

  private def loaded(name: String, text: String): ZIO[Any, Nothing, Either[ConfigError, InstallConfig]] =
    ZIO.scoped(TempDir.scoped("config-loader").flatMap(load(_, name, text).either))

  private def families(result: Either[ConfigError, InstallConfig]): Either[String, Vector[String]] =
    result.map(_.families.map(_.value)).left.map(_.render)

  override def spec: Spec[TestEnvironment, Any] = suite("ConfigLoader")(
    test("applies the Go defaults when release and destination are absent"):
      loaded("fonts.yaml", "families: [JetBrainsMono]\n").map(config =>
        assertTrue(
          config.map(_.selector) == Right(ReleaseSelector.Latest),
          config.map(_.destination.value) == Right("~/.local/share/fonts/NerdFonts"),
          config.map(_.refreshFontCache) == Right(RefreshFontCache.Disabled),
        ),
      )
    ,
    test("an explicit empty string also takes the default, as Go's ApplyDefaults does"):
      loaded("fonts.yaml", "release: ''\ndestination: \"\"\nfamilies: [Hack]\n").map(config =>
        assertTrue(
          config.map(_.selector) == Right(ReleaseSelector.Latest),
          config.map(_.destination.value) == Right("~/.local/share/fonts/NerdFonts"),
        ),
      )
    ,
    test("trims release, destination and every family"):
      val text = "release: ' v3.4.0 '\ndestination: ' /tmp/fonts '\nfamilies: [' Hack ', ' JetBrainsMono ']\n"
      loaded("fonts.yaml", text).map(config =>
        assertTrue(
          config.map(_.selector.render) == Right("v3.4.0"),
          config.map(_.destination.value) == Right("/tmp/fonts"),
          families(config) == Right(Vector("Hack", "JetBrainsMono")),
        ),
      )
    ,
    test("reads the literal refresh_font_cache key from a YAML boolean"):
      loaded("fonts.yaml", "refresh_font_cache: true\nfamilies: [Hack]\n").map(config =>
        assertTrue(config.map(_.refreshFontCache) == Right(RefreshFontCache.Enabled)),
      )
    ,
    test("does not read a camelCase refreshFontCache key, proving the snake_case binding is what works"):
      loaded("fonts.yaml", "refreshFontCache: true\nfamilies: [Hack]\n").map(config =>
        assertTrue(config.map(_.refreshFontCache) == Right(RefreshFontCache.Disabled)),
      )
    ,
    test("decodes a complete YAML config"):
      val text = "release: v3.4.0\ndestination: /tmp/fonts\nrefresh_font_cache: true\nfamilies: [Hack]\n"
      loaded("fonts.yaml", text).map(config =>
        assertTrue(
          config.map(_.selector.render) == Right("v3.4.0"),
          config.map(_.destination.value) == Right("/tmp/fonts"),
          config.map(_.refreshFontCache) == Right(RefreshFontCache.Enabled),
          families(config) == Right(Vector("Hack")),
        ),
      )
    ,
    test("decodes a complete JSON config leniently through Typesafe Config"):
      val text =
        """{"release":"v3.4.0","destination":"/tmp/fonts","refresh_font_cache":true,"families":["Hack"]}"""
      loaded("fonts.json", text).map(config =>
        assertTrue(
          config.map(_.selector.render) == Right("v3.4.0"),
          config.map(_.destination.value) == Right("/tmp/fonts"),
          config.map(_.refreshFontCache) == Right(RefreshFontCache.Enabled),
          families(config) == Right(Vector("Hack")),
        ),
      )
    ,
    test("decodes a complete HOCON config"):
      val text = "release = v3.4.0\ndestination = /tmp/fonts\nrefresh_font_cache = true\nfamilies = [Hack]\n"
      loaded("fonts.hocon", text).map(config =>
        assertTrue(
          config.map(_.selector.render) == Right("v3.4.0"),
          config.map(_.destination.value) == Right("/tmp/fonts"),
          config.map(_.refreshFontCache) == Right(RefreshFontCache.Enabled),
          families(config) == Right(Vector("Hack")),
        ),
      )
    ,
    test("decodes a .conf file as HOCON, not YAML"):
      loaded("fonts.conf", "families = [Hack, JetBrainsMono]\n").map(config =>
        assertTrue(families(config) == Right(Vector("Hack", "JetBrainsMono"))),
      )
    ,
    test("decodes an uppercase .JSON extension case-insensitively"):
      loaded("fonts.JSON", """{"families":["Hack"]}""").map(config =>
        assertTrue(families(config) == Right(Vector("Hack"))),
      )
    ,
    test("coerces a bare scalar where a family list is expected into a one-element list"):
      loaded("fonts.yaml", "families: FiraCode\n").map(config =>
        assertTrue(families(config) == Right(Vector("FiraCode"))),
      )
    ,
    test("accepts an unknown key rather than rejecting it"):
      loaded("fonts.yaml", "families: [Hack]\nfont_family: Hack\n").map(config =>
        assertTrue(families(config) == Right(Vector("Hack"))),
      )
    ,
    test("rejects a release that is blank after trimming"):
      loaded("fonts.yaml", "release: '   '\ndestination: /tmp/fonts\nfamilies: [Hack]\n").map(result =>
        assertTrue(result.left.map(_.render) == Left("release is required")),
      )
    ,
    test("rejects a destination that is blank after trimming"):
      loaded("fonts.yaml", "release: latest\ndestination: '   '\nfamilies: [Hack]\n").map(result =>
        assertTrue(result.left.map(_.render) == Left("destination is required")),
      )
    ,
    test("rejects a family that is blank after trimming"):
      loaded("fonts.yaml", "release: latest\ndestination: /tmp/fonts\nfamilies: ['   ']\n").map(result =>
        assertTrue(result.left.map(_.render) == Left("font family names cannot be empty")),
      )
    ,
    test("an empty family list reaches the app's own at-least-one validation"):
      loaded("fonts.yaml", "families: []\n").map(result =>
        assertTrue(result.left.map(_.render) == Left("at least one font family is required")),
      )
    ,
    test("rejects duplicate families"):
      loaded("fonts.yaml", "families: [Hack, Hack]\n").map(result =>
        assertTrue(result.left.map(_.render) == Left("duplicate font family \"Hack\"")),
      )
    ,
    test("rejects duplicate families that only differ by surrounding whitespace"):
      loaded("fonts.yaml", "families: [Hack, ' Hack ']\n").map(result =>
        assertTrue(result.left.map(_.render) == Left("duplicate font family \"Hack\"")),
      )
    ,
    test("rejects an unsafe family name with the family-name message"):
      loaded("fonts.yaml", "families: ['Hack/Regular']\n").map(result =>
        assertTrue(result.left.exists {
          case ConfigError.Invalid(_, ConfigValidationError.InvalidFamily(_)) => true
          case _                                                              => false
        }),
      )
    ,
    test("an empty file is a document with every key absent"):
      loaded("fonts.yaml", "").map(result =>
        assertTrue(result.left.map(_.render) == Left("at least one font family is required")),
      )
    ,
    test("a validation failure carries the path but renders only the Go message"):
      ZIO.scoped(TempDir.scoped("config-loader").flatMap { dir =>
        load(dir, "fonts.yaml", "families: []\n").either.map(result =>
          assertTrue(
            result.left.map(_.render) == Left("at least one font family is required"),
            result.left.exists {
              case ConfigError.Invalid(path, _) => path == dir / "fonts.yaml"
              case _                            => false
            },
          ),
        )
      })
    ,
    test("a missing file is NotFound and renders Go's open error"):
      ZIO.scoped(TempDir.scoped("config-loader").flatMap { dir =>
        val path = dir / "missing.yaml"
        ConfigLoader
          .load(path)
          .either
          .map(result =>
            assertTrue(
              result == Left(ConfigError.NotFound(path)),
              ConfigError.NotFound(path).render == s"open $path: no such file or directory",
            ),
          )
      })
    ,
    test("a path that exists but cannot be read as a file is Unreadable, not NotFound"):
      ZIO.scoped(TempDir.scoped("config-loader").flatMap { dir =>
        val directory = dir / "fonts.yaml"
        ZIO.attemptBlockingIO(os.makeDir(directory)).orDie *> ConfigLoader
          .load(directory)
          .either
          .map(result =>
            assertTrue(result.left.exists {
              case ConfigError.Unreadable(path, _) => path == directory
              case _                               => false
            }),
          )
      })
    ,
    test("an unknown extension is a hard error, not a silent YAML guess"):
      ZIO.scoped(TempDir.scoped("config-loader").flatMap { dir =>
        load(dir, "fonts.txt", "families: [Hack]\n").either
          .map(result => assertTrue(result == Left(ConfigError.UnsupportedFormat(dir / "fonts.txt", ".txt"))))
      })
    ,
    test("a missing extension is a hard error"):
      ZIO.scoped(TempDir.scoped("config-loader").flatMap { dir =>
        load(dir, "fonts", "families: [Hack]\n").either
          .map(result => assertTrue(result == Left(ConfigError.UnsupportedFormat(dir / "fonts", ""))))
      })
    ,
    test("a malformed YAML file is a one-line Parse error"):
      parseFailure("fonts.yaml", "release: [\n")
    ,
    test("a malformed JSON file is a one-line Parse error"):
      parseFailure("fonts.json", """{"families": [""")
    ,
    test("a malformed HOCON file is a one-line Parse error"):
      parseFailure("fonts.conf", "families = [\n")
    ,
    test("the shipped config.example.yaml loads with the values it documents"):
      val example = sys.props
        .get("nerdfonts.repoRoot")
        .map(os.Path(_) / "config.example.yaml")
        .getOrElse(throw new AssertionError("nerdfonts.repoRoot is not set"))
      ConfigLoader
        .load(example)
        .either
        .map(config =>
          assertTrue(
            config.map(_.selector) == Right(ReleaseSelector.Latest),
            config.map(_.destination.value) == Right("~/.local/share/fonts/NerdFonts"),
            config.map(_.refreshFontCache) == Right(RefreshFontCache.Enabled),
            families(config) == Right(Vector("JetBrainsMono", "Hack", "FiraCode", "Meslo")),
          ),
        ),
  )

  private def parseFailure(name: String, text: String): ZIO[Any, Nothing, zio.test.TestResult] = ZIO.scoped(
    TempDir.scoped("config-loader").flatMap { dir =>
      load(dir, name, text).either.map(result =>
        assertTrue(result.left.exists {
          case ConfigError.Parse(path, message) => path == dir / name && !message.contains("\n")
          case _                                => false
        })
          && assertTrue(result.left.map(_.render).left.exists(_.startsWith(s"parse ${dir / name}: "))),
      )
    },
  )

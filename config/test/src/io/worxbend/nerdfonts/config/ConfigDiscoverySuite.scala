package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.environment.EnvironmentError

import ox.discard

final class ConfigDiscoverySuite extends munit.FunSuite:
  private val tempDir = FunFixture[os.Path](_ => os.temp.dir(prefix = "config-discovery"), os.remove.all(_))

  private def discover(env: Environment): Either[ConfigError, Option[DiscoveredConfig]] =
    ConfigDiscovery.discover(env, ConfigLoader.load)

  private def families(found: DiscoveredConfig): Vector[String] = found.config.families.map(_.value)

  tempDir.test("loads the first candidate that exists and skips the ones that do not"): dir =>
    ConfigFiles.write(dir, "nerd-fonts-installer.yml", "families: [Hack]\n").discard
    ConfigFiles.write(dir, "nerd-fonts-installer.json", """{"families": ["JetBrainsMono"]}""").discard
    val found = discover(Environment.fixed(homeDirectory = None, workingDirectory = Right(dir)))
    assertEquals(found.map(_.map(_.path)), Right(Some(dir / "nerd-fonts-installer.yml")))
    assertEquals(found.map(_.map(families)), Right(Some(Vector("Hack"))))

  tempDir.test("returns None when no candidate exists"): dir =>
    assertEquals(
      discover(Environment.fixed(homeDirectory = None, workingDirectory = Right(dir))),
      Right(None),
    )

  tempDir.test("falls through to the config home when the working directory has nothing"): dir =>
    val cwd   = dir / "cwd"
    val home  = dir / "home"
    os.makeDir.all(cwd)
    val path  = ConfigFiles.write(home / ".config", "nerd-fonts-installer/config.conf", "families: [Hack]\n")
    val found = discover(Environment.fixed(homeDirectory = Some(home), workingDirectory = Right(cwd)))
    assertEquals(found.map(_.map(_.path)), Right(Some(path)))

  tempDir.test("an existing candidate that fails validation is fatal rather than skipped"): dir =>
    ConfigFiles.write(dir, "nerd-fonts-installer.yaml", "families: []\n").discard
    ConfigFiles.write(dir, "nerd-fonts-installer.yml", "families: [Hack]\n").discard
    val result = discover(Environment.fixed(homeDirectory = None, workingDirectory = Right(dir)))
    assertEquals(result.left.map(_.render), Left("at least one font family is required"))
    assert(result.left.exists {
      case ConfigError.Invalid(path, _) => path == dir / "nerd-fonts-installer.yaml"
      case _                            => false
    })

  tempDir.test("an existing candidate that fails to parse is fatal"): dir =>
    ConfigFiles.write(dir, "nerd-fonts-installer.json", """{"families": ["Hack"]} {}""").discard
    val result = discover(Environment.fixed(homeDirectory = None, workingDirectory = Right(dir)))
    assertEquals(
      result.left.map(_.render),
      Left(s"parse ${dir / "nerd-fonts-installer.json"}: multiple json values"),
    )

  tempDir.test("a candidate that exists but is not a readable file is fatal"): dir =>
    os.makeDir(dir / "nerd-fonts-installer.yaml")
    val result = discover(Environment.fixed(homeDirectory = None, workingDirectory = Right(dir)))
    assert(
      result.left.exists {
        case ConfigError.Unreadable(_, _) => true
        case _                            => false
      },
      clue = result,
    )

  test("fails before touching the filesystem when the working directory is unknown"):
    val gone = EnvironmentError.NoWorkingDirectory("gone")
    val env  = Environment.fixed(workingDirectory = Left(gone))
    assertEquals(
      ConfigDiscovery.discover(env, path => fail(s"unexpected load of $path")),
      Left(ConfigError.NoWorkingDirectory(gone)),
    )

  tempDir.test("uses the supplied loader for every candidate it tries"): dir =>
    val env    = Environment.fixed(homeDirectory = None, workingDirectory = Right(dir))
    val tried  = Vector.newBuilder[os.Path]
    val result = ConfigDiscovery.discover(
      env,
      path =>
        tried += path
        Left(ConfigError.NotFound(path)),
    )
    assertEquals(result, Right(None))
    assertEquals(tried.result().size, 8)

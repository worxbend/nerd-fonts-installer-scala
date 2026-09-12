package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment as Env
import io.worxbend.nerdfonts.environment.EnvironmentError

import zio.IO
import zio.Ref
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** Discovery skips only `NotFound`; every other outcome from a candidate that exists is fatal (§4). */
object ConfigDiscoverySuite extends ZIOSpecDefault:
  private def discover(env: Env): IO[ConfigError, Option[DiscoveredConfig]] =
    ConfigDiscovery.discover(env, ConfigLoader.load)

  private def write(dir: os.Path, name: String, text: String): ZIO[Any, Nothing, os.Path] =
    ZIO.attemptBlockingIO(ConfigFiles.write(dir, name, text)).orDie

  private def families(found: DiscoveredConfig): Vector[String] = found.config.families.map(_.value)

  override def spec: Spec[TestEnvironment, Any] = suite("ConfigDiscovery")(
    test("loads the first candidate that exists and skips the ones that do not"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        for
          _     <- write(dir, "nerd-fonts-installer.yml", "families: [Hack]\n")
          _     <- write(dir, "nerd-fonts-installer.json", """{"families": ["JetBrainsMono"]}""")
          found <- discover(Env.fixed(homeDirectory = None, workingDirectory = Right(dir))).either
        yield assertTrue(
          found.map(_.map(_.path)) == Right(Some(dir / "nerd-fonts-installer.yml")),
          found.map(_.map(families)) == Right(Some(Vector("Hack"))),
        )
      })
    ,
    test("returns None when no candidate exists"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        discover(Env.fixed(homeDirectory = None, workingDirectory = Right(dir))).either
          .map(result => assertTrue(result == Right(None)))
      })
    ,
    test("falls through to the config home when the working directory has nothing"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        val cwd  = dir / "cwd"
        val home = dir / "home"
        for
          _     <- ZIO.attemptBlockingIO(os.makeDir.all(cwd)).orDie
          path  <- write(home / ".config", "nerd-fonts-installer/config.conf", "families = [Hack]\n")
          found <- discover(Env.fixed(homeDirectory = Some(home), workingDirectory = Right(cwd))).either
        yield assertTrue(found.map(_.map(_.path)) == Right(Some(path)))
      })
    ,
    test("an existing candidate that fails validation is fatal rather than skipped"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        for
          _      <- write(dir, "nerd-fonts-installer.yaml", "families: []\n")
          _      <- write(dir, "nerd-fonts-installer.yml", "families: [Hack]\n")
          result <- discover(Env.fixed(homeDirectory = None, workingDirectory = Right(dir))).either
        yield assertTrue(
          result.left.map(_.render) == Left("at least one font family is required"),
          result.left.exists {
            case ConfigError.Invalid(path, _) => path == dir / "nerd-fonts-installer.yaml"
            case _                            => false
          },
        )
      })
    ,
    test("an existing candidate that fails to parse is fatal"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        for
          _      <- write(dir, "nerd-fonts-installer.json", """{"families": [""")
          result <- discover(Env.fixed(homeDirectory = None, workingDirectory = Right(dir))).either
        yield assertTrue(result.left.exists {
          case ConfigError.Parse(path, message) =>
            path == dir / "nerd-fonts-installer.json" && !message.contains("\n")
          case _                                => false
        })
      })
    ,
    test("a candidate that exists but is not a readable file is fatal"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        for
          _      <- ZIO.attemptBlockingIO(os.makeDir(dir / "nerd-fonts-installer.yaml")).orDie
          result <- discover(Env.fixed(homeDirectory = None, workingDirectory = Right(dir))).either
        yield assertTrue(result.left.exists {
          case ConfigError.Unreadable(_, _) => true
          case _                            => false
        })
      })
    ,
    test("fails before touching the filesystem when the working directory is unknown"):
      val gone = EnvironmentError.NoWorkingDirectory("gone")
      val env  = Env.fixed(workingDirectory = Left(gone))
      ConfigDiscovery
        .discover(env, path => ZIO.dieMessage(s"unexpected load of $path"))
        .either
        .map(result => assertTrue(result == Left(ConfigError.NoWorkingDirectory(gone))))
    ,
    test("uses the supplied loader for every candidate it tries"):
      ZIO.scoped(TempDir.scoped("config-discovery").flatMap { dir =>
        val env = Env.fixed(homeDirectory = None, workingDirectory = Right(dir))
        for
          tried  <- Ref.make(Vector.empty[os.Path])
          loader  =
            (path: os.Path) => tried.update(_ :+ path) *> ZIO.fail[ConfigError](ConfigError.NotFound(path))
          result <- ConfigDiscovery.discover(env, loader).either
          paths  <- tried.get
        yield assertTrue(result == Right(None), paths.size == 10)
      }),
  )

package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment as Env
import io.worxbend.nerdfonts.environment.EnvironmentError

import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The ordered, de-duplicated candidate list (§4); five extensions in two shapes across two locations. */
object ConfigLocationsSuite extends ZIOSpecDefault:
  private val cwd  = os.Path("/work/cwd")
  private val home = os.Path("/home/test")
  private val xdg  = os.Path("/work/xdg")

  private def shapes(base: os.Path): Vector[os.Path] = Vector(
    base / "nerd-fonts-installer.yaml",
    base / "nerd-fonts-installer.yml",
    base / "nerd-fonts-installer.json",
    base / "nerd-fonts-installer.conf",
    base / "nerd-fonts-installer.hocon",
    base / "nerd-fonts-installer" / "config.yaml",
    base / "nerd-fonts-installer" / "config.yml",
    base / "nerd-fonts-installer" / "config.json",
    base / "nerd-fonts-installer" / "config.conf",
    base / "nerd-fonts-installer" / "config.hocon",
  )

  private def env(
      variables: Map[String, String] = Map.empty,
      home: Option[os.Path] = Some(home),
      cwd: Either[EnvironmentError, os.Path] = Right(cwd),
  ): Env = Env.fixed(variables, home, cwd)

  override def spec: Spec[TestEnvironment, Any] = suite("ConfigLocations")(
    test("searches the working directory before the config home, flat files before the directory shape"):
      ConfigLocations
        .candidates(env(Map("XDG_CONFIG_HOME" -> xdg.toString)))
        .map(candidates => assertTrue(candidates == shapes(cwd) ++ shapes(xdg)))
    ,
    test("falls back to ~/.config when XDG_CONFIG_HOME is unset"):
      ConfigLocations
        .candidates(env())
        .map(candidates => assertTrue(candidates == shapes(cwd) ++ shapes(home / ".config")))
    ,
    test("falls back to ~/.config when XDG_CONFIG_HOME is empty"):
      ConfigLocations
        .candidates(env(Map("XDG_CONFIG_HOME" -> "")))
        .map(candidates => assertTrue(candidates == shapes(cwd) ++ shapes(home / ".config")))
    ,
    test("ignores a relative XDG_CONFIG_HOME"):
      ConfigLocations
        .candidates(env(Map("XDG_CONFIG_HOME" -> "relative")))
        .map(candidates => assertTrue(candidates == shapes(cwd) ++ shapes(home / ".config")))
    ,
    test("uses an absolute XDG_CONFIG_HOME even without a home directory"):
      ConfigLocations
        .candidates(env(Map("XDG_CONFIG_HOME" -> xdg.toString), home = None))
        .map(candidates => assertTrue(candidates == shapes(cwd) ++ shapes(xdg)))
    ,
    test("omits the config-home candidates when the home directory is unknown"):
      ConfigLocations.candidates(env(home = None)).map(candidates => assertTrue(candidates == shapes(cwd)))
    ,
    test("de-duplicates to twenty down to ten when the working directory is the config home"):
      ConfigLocations
        .candidates(env(Map("XDG_CONFIG_HOME" -> cwd.toString)))
        .map(candidates => assertTrue(candidates == shapes(cwd), candidates.size == 10))
    ,
    test("fails with the Go prefix when the working directory cannot be determined"):
      val gone = EnvironmentError.NoWorkingDirectory("getwd: no such file or directory")
      ConfigLocations
        .candidates(env(cwd = Left(gone)))
        .either
        .map(result =>
          assertTrue(
            result.left.map(_.render) == Left("locate current directory: getwd: no such file or directory"),
          ),
        ),
  )

package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.environment.EnvironmentError

final class ConfigLocationsSuite extends munit.FunSuite:
  private val cwd  = os.Path("/work/cwd")
  private val home = os.Path("/home/test")
  private val xdg  = os.Path("/work/xdg")

  private def shapes(base: os.Path): Vector[os.Path] = Vector(
    base / "nerd-fonts-installer.yaml",
    base / "nerd-fonts-installer.yml",
    base / "nerd-fonts-installer.json",
    base / "nerd-fonts-installer.conf",
    base / "nerd-fonts-installer" / "config.yaml",
    base / "nerd-fonts-installer" / "config.yml",
    base / "nerd-fonts-installer" / "config.json",
    base / "nerd-fonts-installer" / "config.conf",
  )

  private def env(
      variables: Map[String, String] = Map.empty,
      home: Option[os.Path] = Some(home),
      cwd: Either[EnvironmentError, os.Path] = Right(cwd),
  ): Environment = Environment.fixed(variables, home, cwd)

  test("searches the working directory before the config home, flat files before the directory shape"):
    val candidates = ConfigLocations.candidates(env(Map("XDG_CONFIG_HOME" -> xdg.toString)))
    assertEquals(candidates, Right(shapes(cwd) ++ shapes(xdg)))

  test("falls back to ~/.config when XDG_CONFIG_HOME is unset"):
    assertEquals(ConfigLocations.candidates(env()), Right(shapes(cwd) ++ shapes(home / ".config")))

  test("falls back to ~/.config when XDG_CONFIG_HOME is empty"):
    val candidates = ConfigLocations.candidates(env(Map("XDG_CONFIG_HOME" -> "")))
    assertEquals(candidates, Right(shapes(cwd) ++ shapes(home / ".config")))

  test("ignores a relative XDG_CONFIG_HOME"):
    val candidates = ConfigLocations.candidates(env(Map("XDG_CONFIG_HOME" -> "relative")))
    assertEquals(candidates, Right(shapes(cwd) ++ shapes(home / ".config")))

  test("uses an absolute XDG_CONFIG_HOME even without a home directory"):
    val candidates = ConfigLocations.candidates(env(Map("XDG_CONFIG_HOME" -> xdg.toString), home = None))
    assertEquals(candidates, Right(shapes(cwd) ++ shapes(xdg)))

  test("omits the config-home candidates when the home directory is unknown"):
    assertEquals(ConfigLocations.candidates(env(home = None)), Right(shapes(cwd)))

  test("de-duplicates when the working directory is the config home"):
    val candidates = ConfigLocations.candidates(env(Map("XDG_CONFIG_HOME" -> cwd.toString)))
    assertEquals(candidates, Right(shapes(cwd)))
    assertEquals(candidates.map(_.size), Right(8))

  test("fails with the Go prefix when the working directory cannot be determined"):
    val gone = EnvironmentError.NoWorkingDirectory("getwd: no such file or directory")
    assertEquals(
      ConfigLocations.candidates(env(cwd = Left(gone))).left.map(_.render),
      Left("locate current directory: getwd: no such file or directory"),
    )

package io.worxbend.nerdfonts.environment

import io.worxbend.nerdfonts.fonts.DestinationPath

import zio.IO
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object PathExpanderSuite extends ZIOSpecDefault:
  private val home = os.Path("/home/test")
  private val cwd  = os.Path("/workspace")
  private val env  = Environment.fixed(homeDirectory = Some(home), workingDirectory = Right(cwd))

  private def expand(
      raw: String,
      environment: io.worxbend.nerdfonts.environment.Environment = env,
  ): IO[PathError, os.Path] = DestinationPath.parse(raw) match
    case Some(path) => PathExpander.expand(path, environment)
    case None       => ZIO.die(AssertionError(s"blank input $raw"))

  def spec = suite("PathExpander")(
    test("a bare tilde is the home directory"):
      expand("~").either.map(result => assertTrue(result == Right(home)))
    ,
    test("a leading tilde-slash is joined onto the home directory"):
      expand("~/.local/share/fonts").either.map(result =>
        assertTrue(result == Right(home / ".local" / "share" / "fonts")),
      )
    ,
    test("a tilde-slash path is normalised like filepath.Join"):
      expand("~//a/../b").either.map(result => assertTrue(result == Right(home / "b")))
    ,
    test("a tilde followed by a user name is not expanded"):
      expand("~alice/fonts").either.map(result => assertTrue(result == Right(cwd / "~alice" / "fonts")))
    ,
    test("an embedded tilde is not expanded"):
      expand("fonts/~/x").either.map(result => assertTrue(result == Right(cwd / "fonts" / "~" / "x")))
    ,
    test("an absolute path is returned as is"):
      expand("/usr/share/fonts").either.map(result =>
        assertTrue(result == Right(os.Path("/usr/share/fonts"))),
      )
    ,
    test("an absolute path does not need a working directory"):
      val noCwd = Environment.fixed(workingDirectory = Left(EnvironmentError.NoWorkingDirectory("gone")))
      expand("/usr/share/fonts", noCwd).either.map(result =>
        assertTrue(result == Right(os.Path("/usr/share/fonts"))),
      )
    ,
    test("a relative path resolves against the working directory"):
      expand("fonts").either.map(result => assertTrue(result == Right(cwd / "fonts")))
    ,
    test("a relative path without a working directory fails with the locate-directory prefix"):
      val noCwd = Environment.fixed(workingDirectory = Left(EnvironmentError.NoWorkingDirectory("gone")))
      expand("fonts", noCwd).either.map(result =>
        assertTrue(result.left.map(_.render) == Left("locate current directory: gone")),
      )
    ,
    test("a tilde without a home directory is NoHome"):
      val noHome = Environment.fixed(homeDirectory = None)
      expand("~/fonts", noHome).either.map(result =>
        assertTrue(result == Left(PathError.NoHome), PathError.NoHome.render == "$HOME is not defined"),
      )
    ,
    test("a path the platform rejects is Invalid rather than a thrown exception"):
      expand("fonts\u0000x").either.map(result =>
        assertTrue(result.left.exists(_.isInstanceOf[PathError.Invalid])),
      ),
  )

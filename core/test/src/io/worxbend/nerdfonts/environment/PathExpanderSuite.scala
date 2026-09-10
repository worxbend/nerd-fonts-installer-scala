package io.worxbend.nerdfonts.environment

import io.worxbend.nerdfonts.fonts.DestinationPath

final class PathExpanderSuite extends munit.FunSuite:
  private val home = os.Path("/home/test")
  private val cwd  = os.Path("/workspace")
  private val env  = Environment.fixed(homeDirectory = Some(home), workingDirectory = Right(cwd))

  private def expand(raw: String, environment: Environment = env): Either[PathError, os.Path] =
    DestinationPath.parse(raw).map(PathExpander.expand(_, environment)).getOrElse(fail(s"blank input $raw"))

  test("a bare tilde is the home directory"):
    assertEquals(expand("~"), Right(home))

  test("a leading tilde-slash is joined onto the home directory"):
    assertEquals(expand("~/.local/share/fonts"), Right(home / ".local" / "share" / "fonts"))

  test("a tilde-slash path is normalised like filepath.Join"):
    assertEquals(expand("~//a/../b"), Right(home / "b"))

  test("a tilde followed by a user name is not expanded"):
    assertEquals(expand("~alice/fonts"), Right(cwd / "~alice" / "fonts"))

  test("an embedded tilde is not expanded"):
    assertEquals(expand("fonts/~/x"), Right(cwd / "fonts" / "~" / "x"))

  test("an absolute path is returned as is"):
    assertEquals(expand("/usr/share/fonts"), Right(os.Path("/usr/share/fonts")))

  test("an absolute path does not need a working directory"):
    val noCwd = Environment.fixed(workingDirectory = Left(EnvironmentError.NoWorkingDirectory("gone")))
    assertEquals(expand("/usr/share/fonts", noCwd), Right(os.Path("/usr/share/fonts")))

  test("a relative path resolves against the working directory"):
    assertEquals(expand("fonts"), Right(cwd / "fonts"))

  test("a relative path without a working directory fails with the Go prefix"):
    val noCwd = Environment.fixed(workingDirectory = Left(EnvironmentError.NoWorkingDirectory("gone")))
    assertEquals(expand("fonts", noCwd).left.map(_.render), Left("locate current directory: gone"))

  test("a tilde without a home directory is NoHome"):
    val noHome = Environment.fixed(homeDirectory = None)
    assertEquals(expand("~/fonts", noHome), Left(PathError.NoHome))
    assertEquals(PathError.NoHome.render, "$HOME is not defined")

  test("a path the platform rejects is Invalid rather than a thrown exception"):
    assert(expand("fonts\u0000x").left.exists(_.isInstanceOf[PathError.Invalid]))

package io.worxbend.nerdfonts.environment

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object EnvironmentSuite extends ZIOSpecDefault:
  def spec = suite("Environment")(
    test("a fixed environment answers exactly what it was given"):
      val env = Environment.fixed(
        variables = Map("NO_COLOR" -> "1"),
        homeDirectory = Some(os.Path("/h")),
        workingDirectory = Right(os.Path("/w")),
        properties = Map("java.io.tmpdir" -> "/scratch"),
      )
      for
        noColor  <- env.variable("NO_COLOR")
        term     <- env.variable("TERM")
        home     <- env.homeDirectory
        working  <- env.workingDirectory.either
        tmpdir   <- env.property("java.io.tmpdir")
        userHome <- env.property("user.home")
      yield assertTrue(
        noColor == Some("1"),
        term == None,
        home == Some(os.Path("/h")),
        working == Right(os.Path("/w")),
        tmpdir == Some("/scratch"),
        userHome == None,
      )
    ,
    test("the system environment reports the process working directory"):
      Environment.System.workingDirectory.either.map(working => assertTrue(working == Right(os.pwd)))
    ,
    test("the system environment resolves an absolute home directory"):
      Environment.System.homeDirectory.map(home => assertTrue(home.exists(_.toString.startsWith("/"))))
    ,
    test("the system environment reads real variables"):
      Environment.System.variable("PATH").map(value => assertTrue(value == sys.env.get("PATH")))
    ,
    test("the system environment reads real properties"):
      Environment.System
        .property("java.io.tmpdir")
        .map(value => assertTrue(value == sys.props.get("java.io.tmpdir")))
    ,
    test("$HOME unset means no home, with no fallback to the JVM's user.home"):
      assertTrue(Environment.homeFrom(None) == None)
    ,
    test("a blank $HOME means no home"):
      assertTrue(Environment.homeFrom(Some("")) == None)
    ,
    test("a non-blank $HOME resolves to that path"):
      assertTrue(Environment.homeFrom(Some("/home/nerd")) == Some(os.Path("/home/nerd")))
    ,
    test("an environment error renders its cause"):
      assertTrue(EnvironmentError.NoWorkingDirectory("boom").render == "boom"),
  )

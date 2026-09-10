package io.worxbend.nerdfonts.environment

final class EnvironmentSuite extends munit.FunSuite:
  test("a fixed environment answers exactly what it was given"):
    val env = Environment.fixed(
      variables = Map("NO_COLOR" -> "1"),
      homeDirectory = Some(os.Path("/h")),
      workingDirectory = Right(os.Path("/w")),
    )
    assertEquals(env.variable("NO_COLOR"), Some("1"))
    assertEquals(env.variable("TERM"), None)
    assertEquals(env.homeDirectory, Some(os.Path("/h")))
    assertEquals(env.workingDirectory, Right(os.Path("/w")))

  test("the system environment reports the process working directory"):
    assertEquals(Environment.System.workingDirectory, Right(os.pwd))

  test("the system environment resolves an absolute home directory"):
    assert(Environment.System.homeDirectory.exists(_.toString.startsWith("/")))

  test("the system environment reads real variables"):
    assertEquals(Environment.System.variable("PATH"), sys.env.get("PATH"))

  test("an environment error renders its cause"):
    assertEquals(EnvironmentError.NoWorkingDirectory("boom").render, "boom")

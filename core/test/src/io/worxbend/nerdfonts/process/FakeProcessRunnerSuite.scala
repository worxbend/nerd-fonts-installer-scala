package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.process.FakeProcessRunner.Script

final class FakeProcessRunnerSuite extends munit.FunSuite:
  private val sttySize = Script.succeeding(Vector("stty", "size"), stdout = "24 80\n")
  private val fcCache  = Script.exiting(Vector("fc-cache"), code = 1)

  test("answers with the first script whose prefix matches the command"):
    val runner = FakeProcessRunner(Vector(sttySize, fcCache))
    val spec   = ProcessSpec(Vector("stty", "size"), stdout = Stdout.Capture)
    assertEquals(runner.run(spec), Right(ProcessResult(ExitStatus.success, "24 80\n")))
    assertEquals(runner.run(ProcessSpec(Vector("fc-cache", "-f", "/fonts"))).map(_.exit.code), Right(1))

  test("an unscripted command is NotFound"):
    assertEquals(
      FakeProcessRunner().run(ProcessSpec(Vector("stty", "raw"))),
      Left(ProcessError.NotFound("stty")),
    )

  test("records every call in order"):
    val runner = FakeProcessRunner(Vector(sttySize))
    val first  = ProcessSpec(Vector("stty", "size"))
    val second = ProcessSpec(Vector("stty", "raw", "-echo"))
    assertEquals(runner.run(first).isRight, true)
    assertEquals(runner.run(second).isLeft, true)
    assertEquals(runner.calls, Vector(first, second))

  test("lookPath answers from the executables table"):
    val runner = FakeProcessRunner(executables = Map("fc-cache" -> os.Path("/usr/bin/fc-cache")))
    assertEquals(runner.lookPath("fc-cache"), Some(os.Path("/usr/bin/fc-cache")))
    assertEquals(runner.lookPath("stty"), None)

  test("a failing script returns its error"):
    val runner =
      FakeProcessRunner(Vector(Script.failing(Vector("stty"), ProcessError.Failed("stty", "no tty"))))
    assertEquals(runner.run(ProcessSpec(Vector("stty", "-g"))), Left(ProcessError.Failed("stty", "no tty")))

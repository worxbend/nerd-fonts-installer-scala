package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.process.FakeProcessRunner.Script

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object FakeProcessRunnerSuite extends ZIOSpecDefault:
  private val sttySize = Script.succeeding(Vector("stty", "size"), stdout = "24 80\n")
  private val fcCache  = Script.exiting(Vector("fc-cache"), code = 1)

  def spec = suite("FakeProcessRunner")(
    test("answers with the first script whose prefix matches the command"):
      val runner = FakeProcessRunner(Vector(sttySize, fcCache))
      val spec   = ProcessSpec(Vector("stty", "size"), stdout = Stdout.Capture)
      for
        sizeResult  <- runner.run(spec).either
        fcCacheCode <- runner.run(ProcessSpec(Vector("fc-cache", "-f", "/fonts"))).map(_.exit.code).either
      yield assertTrue(
        sizeResult == Right(ProcessResult(ExitStatus.success, "24 80\n")),
        fcCacheCode == Right(1),
      )
    ,
    test("an unscripted command is NotFound"):
      FakeProcessRunner()
        .run(ProcessSpec(Vector("stty", "raw")))
        .either
        .map(result => assertTrue(result == Left(ProcessError.NotFound("stty"))))
    ,
    test("records every call in order"):
      val runner = FakeProcessRunner(Vector(sttySize))
      val first  = ProcessSpec(Vector("stty", "size"))
      val second = ProcessSpec(Vector("stty", "raw", "-echo"))
      for
        firstResult  <- runner.run(first).either
        secondResult <- runner.run(second).either
      yield assertTrue(
        firstResult.isRight,
        secondResult.isLeft,
        runner.calls == Vector(first, second),
      )
    ,
    test("lookPath answers from the executables table"):
      val runner = FakeProcessRunner(executables = Map("fc-cache" -> os.Path("/usr/bin/fc-cache")))
      for
        fcCache <- runner.lookPath("fc-cache")
        stty    <- runner.lookPath("stty")
      yield assertTrue(fcCache == Some(os.Path("/usr/bin/fc-cache")), stty == None)
    ,
    test("a failing script returns its error"):
      val runner =
        FakeProcessRunner(Vector(Script.failing(Vector("stty"), ProcessError.Failed("stty", "no tty"))))
      runner
        .run(ProcessSpec(Vector("stty", "-g")))
        .either
        .map(result => assertTrue(result == Left(ProcessError.Failed("stty", "no tty")))),
  )

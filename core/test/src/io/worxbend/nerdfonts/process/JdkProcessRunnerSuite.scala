package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.environment.Environment

import java.nio.file.attribute.PosixFilePermission

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import zio.UIO
import zio.ZIO
import zio.test.TestResult
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** Real subprocesses, but only POSIX-standard ones (`echo`, `cat`, `false`, `ls`) and never through a shell. */
object JdkProcessRunnerSuite extends ZIOSpecDefault:
  private val systemPath =
    Environment.fixed(variables = Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin")))
  private val runner     = JdkProcessRunner(systemPath)

  private def withTempDir(use: os.Path => UIO[TestResult]): UIO[TestResult] = ZIO.acquireReleaseWith(
    ZIO.attemptBlocking(os.temp.dir(prefix = "process-runner")).orDie,
  )(dir => ZIO.attemptBlocking(os.remove.all(dir)).orDie)(use)

  def spec = suite("JdkProcessRunner")(
    test("captures stdout as UTF-8 when asked"):
      runner
        .run(ProcessSpec(Vector("echo", "héllo"), stdout = Stdout.Capture))
        .either
        .map(result => assertTrue(result == Right(ProcessResult(ExitStatus.success, "héllo\n"))))
    ,
    test("leaves stdout empty when it is inherited"):
      runner
        .run(ProcessSpec(Vector("true")))
        .map(_.stdout)
        .either
        .map(result => assertTrue(result == Right("")))
    ,
    test("reports a non-zero exit as a result, not an error"):
      runner
        .run(ProcessSpec(Vector("false")))
        .either
        .map(result =>
          assertTrue(
            result.map(_.exit.code) == Right(1),
            result.map(_.exit.isSuccess) == Right(false),
            result.map(_.exit.render) == Right("exit status 1"),
          ),
        )
    ,
    test("a program that is not on PATH is NotFound"):
      runner
        .run(ProcessSpec(Vector("definitely-not-installed-xyz")))
        .either
        .map(result => assertTrue(result == Left(ProcessError.NotFound("definitely-not-installed-xyz"))))
    ,
    test("an empty command is Failed"):
      runner
        .run(ProcessSpec(Vector.empty))
        .either
        .map(result => assertTrue(result == Left(ProcessError.Failed("", "empty command"))))
    ,
    test("feeds stdin from a file"):
      withTempDir: dir =>
        val input = dir / "input.txt"
        for
          _      <- ZIO.attemptBlocking(os.write(input, "from file")).orDie
          spec    = ProcessSpec(Vector("cat"), stdin = Stdin.FromFile(input), stdout = Stdout.Capture)
          result <- runner.run(spec).map(_.stdout).either
        yield assertTrue(result == Right("from file"))
    ,
    test("discards stderr when asked and still reports the exit status"):
      withTempDir: dir =>
        val spec = ProcessSpec(
          Vector("ls", (dir / "missing").toString),
          stderr = Stderr.Discard,
          stdout = Stdout.Capture,
        )
        runner.run(spec).either.map(result => assertTrue(result.exists(!_.exit.isSuccess)))
    ,
    test("lookPath finds an executable file on PATH and ignores a plain file"):
      withTempDir: dir =>
        val tool  = dir / "mytool"
        val plain = dir / "notes"
        for
          _          <- ZIO.attemptBlocking(os.write(tool, "#!/bin/sh\n")).orDie
          _          <- ZIO.attemptBlocking(os.write(plain, "text")).orDie
          _          <- ZIO
                          .attemptBlocking(
                            os.perms
                              .set(tool, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE).asJava),
                          )
                          .orDie
          env         = Environment.fixed(variables = Map("PATH" -> dir.toString))
          foundTool  <- JdkProcessRunner(env).lookPath("mytool")
          foundNotes <- JdkProcessRunner(env).lookPath("notes")
        yield assertTrue(foundTool == Some(tool), foundNotes == None)
    ,
    test("lookPath checks a name with a slash directly instead of searching PATH"):
      withTempDir: dir =>
        val tool = dir / "direct"
        for
          _           <- ZIO.attemptBlocking(os.write(tool, "#!/bin/sh\n")).orDie
          _           <- ZIO
                           .attemptBlocking(
                             os.perms
                               .set(tool, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE).asJava),
                           )
                           .orDie
          env          = Environment.fixed(variables = Map.empty)
          foundDirect <- JdkProcessRunner(env).lookPath(tool.toString)
          foundAbsent <- JdkProcessRunner(env).lookPath((dir / "absent").toString)
        yield assertTrue(foundDirect == Some(tool), foundAbsent == None)
    ,
    test("lookPath without a PATH variable finds nothing"):
      JdkProcessRunner(Environment.fixed()).lookPath("echo").map(found => assertTrue(found == None))
    ,
    test("process errors render the program name"):
      assertTrue(
        ProcessError.NotFound("fc-cache").render == "fc-cache: executable file not found in PATH",
        ProcessError.Failed("fc-cache", "permission denied").render == "fc-cache: permission denied",
      )
    ,
    test("a relative or empty PATH entry is never searched, so a cwd binary cannot shadow the real one"):
      withTempDir: dir =>
        val impostor = dir / "fc-cache"
        for
          _      <- ZIO.attemptBlocking(os.write(impostor, "#!/bin/sh\necho pwned\n")).orDie
          _      <- ZIO
                      .attemptBlocking(
                        os.perms
                          .set(
                            impostor,
                            Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE).asJava,
                          ),
                      )
                      .orDie
          env     = Environment.fixed(variables = Map("PATH" -> ":/nonexistent"), workingDirectory = Right(dir))
          found  <- JdkProcessRunner(env).lookPath("fc-cache")
          result <- JdkProcessRunner(env).run(ProcessSpec(Vector("fc-cache"))).either
        yield assertTrue(found == None, result == Left(ProcessError.NotFound("fc-cache")))
    ,
    test(
      "interrupting a captured read of a long-running child's stdout pipe returns promptly and destroys it",
    ):
      // `sleep` never writes to or closes stdout until it exits, so `readAllBytes` blocks in exactly the
      // non-interruptible pipe read the fix targets, until the process is destroyed and the pipe is EOFed.
      runInterruptedAndAssertReaped(ProcessSpec(Vector("sleep", "30"), stdout = Stdout.Capture), "sleep")
    ,
    test("interrupting a waitFor on a long-running inherited-stream child destroys and reaps it"):
      runInterruptedAndAssertReaped(ProcessSpec(Vector("sleep", "30")), "sleep"),
  )

  // The fiber is interrupted (ZIO's fiber-level analogue of `Thread.interrupt()` on the raw thread the old
  // munit version used), and `Fiber#interrupt` itself only returns once the interruption has fully taken
  // hold — including `JdkProcessRunner`'s own `destroyAndReap` — so there is nothing left to await afterwards
  // beyond the descendant actually leaving the process table.
  private def runInterruptedAndAssertReaped(spec: ProcessSpec, descendantHint: String): UIO[TestResult] =
    for
      fiber <- runner.run(spec).fork
      _     <- awaitCondition(s"$descendantHint to start")(hasDescendant(descendantHint))
      exit  <- fiber.interrupt
      _     <- awaitCondition(s"$descendantHint to be reaped")(!hasDescendant(descendantHint))
    yield assertTrue(exit.isInterrupted)

  private def hasDescendant(commandHint: String): Boolean = java.lang.ProcessHandle
    .current()
    .descendants()
    .anyMatch(handle =>
      handle.info().command().toScala.exists(_.contains(commandHint)) ||
        handle.info().commandLine().toScala.exists(_.contains(commandHint)),
    )

  private val pollBudgetNanos: Long = 5_000_000_000L

  private def awaitCondition(what: String)(condition: => Boolean): UIO[Unit] =
    ZIO.attemptBlocking(pollUntil(what, System.nanoTime() + pollBudgetNanos)(condition)).orDie

  @tailrec
  private def pollUntil(what: String, deadlineNanos: Long)(condition: => Boolean): Unit =
    if condition then ()
    else if System.nanoTime() > deadlineNanos then throw AssertionError(s"timed out waiting for $what")
    else
      Thread.sleep(10)
      pollUntil(what, deadlineNanos)(condition)

package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.environment.Environment

import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Real subprocesses, but only POSIX-standard ones (`echo`, `cat`, `false`, `ls`) and never through a shell. */
final class JdkProcessRunnerSuite extends munit.FunSuite:
  private val systemPath =
    Environment.fixed(variables = Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin")))
  private val runner     = JdkProcessRunner(systemPath)

  private val tempDir = FunFixture[os.Path](_ => os.temp.dir(prefix = "process-runner"), os.remove.all(_))

  test("captures stdout as UTF-8 when asked"):
    assertEquals(
      runner.run(ProcessSpec(Vector("echo", "héllo"), stdout = Stdout.Capture)),
      Right(ProcessResult(ExitStatus.success, "héllo\n")),
    )

  test("leaves stdout empty when it is inherited"):
    assertEquals(runner.run(ProcessSpec(Vector("true"))).map(_.stdout), Right(""))

  test("reports a non-zero exit as a result, not an error"):
    val result = runner.run(ProcessSpec(Vector("false")))
    assertEquals(result.map(_.exit.code), Right(1))
    assertEquals(result.map(_.exit.isSuccess), Right(false))
    assertEquals(result.map(_.exit.render), Right("exit status 1"))

  test("a program that is not on PATH is NotFound"):
    assertEquals(
      runner.run(ProcessSpec(Vector("definitely-not-installed-xyz"))),
      Left(ProcessError.NotFound("definitely-not-installed-xyz")),
    )

  test("an empty command is Failed"):
    assertEquals(runner.run(ProcessSpec(Vector.empty)), Left(ProcessError.Failed("", "empty command")))

  tempDir.test("feeds stdin from a file"): dir =>
    val input = dir / "input.txt"
    os.write(input, "from file")
    val spec  = ProcessSpec(Vector("cat"), stdin = Stdin.FromFile(input), stdout = Stdout.Capture)
    assertEquals(runner.run(spec).map(_.stdout), Right("from file"))

  tempDir.test("discards stderr when asked and still reports the exit status"): dir =>
    val spec =
      ProcessSpec(Vector("ls", (dir / "missing").toString), stderr = Stderr.Discard, stdout = Stdout.Capture)
    assert(runner.run(spec).exists(!_.exit.isSuccess))

  tempDir.test("lookPath finds an executable file on PATH and ignores a plain file"): dir =>
    val tool  = dir / "mytool"
    val plain = dir / "notes"
    os.write(tool, "#!/bin/sh\n")
    os.write(plain, "text")
    os.perms.set(tool, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE).asJava)
    val env   = Environment.fixed(variables = Map("PATH" -> dir.toString))
    assertEquals(JdkProcessRunner(env).lookPath("mytool"), Some(tool))
    assertEquals(JdkProcessRunner(env).lookPath("notes"), None)

  tempDir.test("lookPath checks a name with a slash directly instead of searching PATH"): dir =>
    val tool = dir / "direct"
    os.write(tool, "#!/bin/sh\n")
    os.perms.set(tool, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE).asJava)
    val env  = Environment.fixed(variables = Map.empty)
    assertEquals(JdkProcessRunner(env).lookPath(tool.toString), Some(tool))
    assertEquals(JdkProcessRunner(env).lookPath((dir / "absent").toString), None)

  test("lookPath without a PATH variable finds nothing"):
    assertEquals(JdkProcessRunner(Environment.fixed()).lookPath("echo"), None)

  test("process errors render the program name"):
    assertEquals(ProcessError.NotFound("fc-cache").render, "fc-cache: executable file not found in PATH")
    assertEquals(ProcessError.Failed("fc-cache", "permission denied").render, "fc-cache: permission denied")

  tempDir.test("a relative or empty PATH entry is never searched, so a cwd binary cannot shadow the real one"):
    dir =>
      val impostor = dir / "fc-cache"
      os.write(impostor, "#!/bin/sh\necho pwned\n")
      os.perms.set(impostor, Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE).asJava)
      val env = Environment.fixed(variables = Map("PATH" -> ":/nonexistent"), workingDirectory = Right(dir))
      assertEquals(JdkProcessRunner(env).lookPath("fc-cache"), None)
      assertEquals(
        JdkProcessRunner(env).run(ProcessSpec(Vector("fc-cache"))),
        Left(ProcessError.NotFound("fc-cache")),
      )

  test(
    "interrupting a captured read of a long-running child's stdout pipe returns promptly and destroys it",
  ):
    // `sleep` never writes to or closes stdout until it exits, so `readAllBytes` blocks in exactly the
    // non-interruptible pipe read the fix targets, until the process is destroyed and the pipe is EOFed.
    runInterruptedAndAssertReaped(ProcessSpec(Vector("sleep", "30"), stdout = Stdout.Capture), "sleep")

  test("interrupting a waitFor on a long-running inherited-stream child destroys and reaps it"):
    runInterruptedAndAssertReaped(ProcessSpec(Vector("sleep", "30")), "sleep")

  private def runInterruptedAndAssertReaped(spec: ProcessSpec, descendantHint: String): Unit =
    val outcome = AtomicReference[Option[Either[InterruptedException, Either[ProcessError, ProcessResult]]]](
      None,
    )
    val worker  = Thread: () =>
      val result =
        try Right(runner.run(spec))
        catch case interrupted: InterruptedException => Left(interrupted)
      outcome.set(Some(result))
    worker.start()
    awaitCondition(s"$descendantHint to start", 5.seconds)(hasDescendant(descendantHint))
    worker.interrupt()
    worker.join(5.seconds.toMillis)
    assert(!worker.isAlive, "run did not return within five seconds of the interrupt")
    assert(outcome.get().exists(_.isLeft), s"expected InterruptedException, got ${outcome.get()}")
    awaitCondition(s"$descendantHint to be reaped", 5.seconds)(!hasDescendant(descendantHint))

  private def hasDescendant(commandHint: String): Boolean = java.lang.ProcessHandle
    .current()
    .descendants()
    .anyMatch(handle =>
      handle.info().command().toScala.exists(_.contains(commandHint)) ||
        handle.info().commandLine().toScala.exists(_.contains(commandHint)),
    )

  @scala.annotation.tailrec
  private def awaitCondition(what: String, remaining: FiniteDuration)(condition: => Boolean): Unit =
    if condition then ()
    else if remaining <= Duration.Zero then fail(s"timed out waiting for $what")
    else
      Thread.sleep(10)
      awaitCondition(what, remaining - 10.millis)(condition)

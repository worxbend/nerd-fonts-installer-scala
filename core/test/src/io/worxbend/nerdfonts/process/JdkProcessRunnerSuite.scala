package io.worxbend.nerdfonts.process

import io.worxbend.nerdfonts.environment.Environment

import java.nio.file.attribute.PosixFilePermission
import scala.jdk.CollectionConverters.*

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

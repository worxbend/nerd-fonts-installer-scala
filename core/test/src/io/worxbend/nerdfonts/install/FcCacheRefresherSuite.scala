package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.process.ExitStatus
import io.worxbend.nerdfonts.process.FakeProcessRunner
import io.worxbend.nerdfonts.process.FakeProcessRunner.Script
import io.worxbend.nerdfonts.process.ProcessError
import io.worxbend.nerdfonts.process.ProcessSpec
import io.worxbend.nerdfonts.process.Stderr
import io.worxbend.nerdfonts.process.Stdin
import io.worxbend.nerdfonts.process.Stdout

final class FcCacheRefresherSuite extends munit.FunSuite:
  private val root      = os.Path("/fonts")
  private val installed = Map("fc-cache" -> os.Path("/usr/bin/fc-cache"))

  test("fc-cache is unavailable when PATH lookup misses"):
    assertEquals(FcCacheRefresher(FakeProcessRunner()).availability, FontCacheAvailability.Unavailable)

  test("fc-cache is available when PATH lookup hits"):
    val runner = FakeProcessRunner(executables = installed)
    assertEquals(FcCacheRefresher(runner).availability, FontCacheAvailability.Available)

  test("refresh runs `fc-cache -f <root>` with every stream inherited"):
    val runner = FakeProcessRunner(Vector(Script.succeeding(Vector("fc-cache"))), installed)
    assertEquals(FcCacheRefresher(runner).refresh(root), Right(()))
    assertEquals(
      runner.calls,
      Vector(ProcessSpec(Vector("fc-cache", "-f", "/fonts"), Stdin.Inherit, Stdout.Inherit, Stderr.Inherit)),
    )

  test("a non-zero exit is an Exit error"):
    val runner = FakeProcessRunner(Vector(Script.exiting(Vector("fc-cache"), 1)), installed)
    assertEquals(FcCacheRefresher(runner).refresh(root), Left(FontCacheError.Exit(ExitStatus.of(1))))

  test("a launch failure is a Launch error"):
    val error  = ProcessError.Failed("fc-cache", "permission denied")
    val runner = FakeProcessRunner(Vector(Script.failing(Vector("fc-cache"), error)), installed)
    assertEquals(FcCacheRefresher(runner).refresh(root), Left(FontCacheError.Launch(error)))

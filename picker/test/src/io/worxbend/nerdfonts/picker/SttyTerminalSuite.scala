package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.process.FakeProcessRunner
import io.worxbend.nerdfonts.process.FakeProcessRunner.Script
import io.worxbend.nerdfonts.process.ProcessError
import io.worxbend.nerdfonts.process.ProcessResult
import io.worxbend.nerdfonts.process.ProcessRunner
import io.worxbend.nerdfonts.process.ProcessSpec
import io.worxbend.nerdfonts.process.Stdin
import io.worxbend.nerdfonts.process.Stdout

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import scala.concurrent.duration.DurationInt

import ox.discard

/** The stty protocol and the raw-mode output discipline, against a scripted process runner. */
final class SttyTerminalSuite extends munit.FunSuite:
  private val saved = "500:5:bf:8a3b:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0"

  private def runner(scripts: Script*): FakeProcessRunner = FakeProcessRunner(
    Vector(
      Script.succeeding(Vector("stty", "-g"), stdout = saved + "\n"),
      Script.succeeding(Vector("stty", "raw", "-echo")),
      Script.succeeding(Vector("stty", "size"), stdout = "40 120\n"),
      Script.succeeding(Vector("stty", saved)),
    ) ++ scripts,
  )

  /**
   * Answers `throwing` with an `InterruptedException` instead of delegating, to script a process call that
   * is interrupted mid-`waitFor` rather than one that fails normally.
   */
  final private class ThrowingOnCommand(delegate: ProcessRunner, throwing: Vector[String])
      extends ProcessRunner:
    def run(spec: ProcessSpec): Either[ProcessError, ProcessResult] =
      if spec.command == throwing then throw InterruptedException("interrupted")
      else delegate.run(spec)
    def lookPath(name: String): Option[os.Path]                     = delegate.lookPath(name)

  final private class Harness(processes: ProcessRunner, input: String = ""):
    val output          = ByteArrayOutputStream()
    val terminal        = SttyTerminal(
      processes,
      escapeTimeout = 20.millis,
      streams = TerminalStreams(ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output),
    )
    def written: String = output.toString(StandardCharsets.UTF_8)

  test("raw mode saves the settings, enters raw mode, runs the body and restores"):
    val processes = runner()
    val harness   = Harness(processes)
    assertEquals(harness.terminal.withRawMode(_ => "done"), Right("done"))
    assertEquals(
      processes.calls.map(_.command),
      Vector(Vector("stty", "-g"), Vector("stty", "raw", "-echo"), Vector("stty", saved)),
    )

  test("every stty call reads /dev/tty and captures stdout, without a shell"):
    val processes = runner()
    Harness(processes).terminal.withRawMode(raw => raw.size()).discard
    processes.calls.foreach: spec =>
      assertEquals(
        spec,
        ProcessSpec(spec.command, stdin = Stdin.FromFile(os.Path("/dev/tty")), stdout = Stdout.Capture),
      )

  test("the alternate screen and cursor sequences bracket the session"):
    val harness = Harness(runner())
    harness.terminal.withRawMode(_ => ()).discard
    assertEquals(harness.written, "\u001b[?1049h\u001b[?25l\u001b[?25h\u001b[?1049l")

  test("a frame is painted from the home position with CRLF-joined, cleared lines"):
    val harness = Harness(runner())
    harness.terminal.withRawMode(_.write(Frame(Vector("one", "two")))).discard
    assertEquals(
      harness.written,
      "\u001b[?1049h\u001b[?25l\u001b[Hone\u001b[K\r\ntwo\u001b[K\u001b[J\u001b[?25h\u001b[?1049l",
    )

  test("nothing written in raw mode contains a line feed without a preceding carriage return"):
    val harness = Harness(runner())
    harness.terminal
      .withRawMode: raw =>
        raw.write(Frame(Vector("a", "b", "c")))
        raw.write(Frame(Vector("d")))
      .discard
    val bytes   = harness.written
    bytes.zipWithIndex.foreach: (char, index) =>
      if char == '\n' then
        assert(
          index > 0 && bytes(index - 1) == '\r',
          s"bare newline at $index in ${bytes.replace("\u001b", "ESC")}",
        )

  test("the terminal is restored even when the body throws"):
    val processes = runner()
    val harness   = Harness(processes)
    val thrown    = intercept[IllegalStateException]:
      harness.terminal.withRawMode(_ => throw IllegalStateException("boom")).discard
    assertEquals(thrown.getMessage, "boom")
    assertEquals(processes.calls.last.command, Vector("stty", saved))
    assert(harness.written.endsWith("\u001b[?25h\u001b[?1049l"), harness.written)

  test("size comes from stty size as rows then columns"):
    assertEquals(Harness(runner()).terminal.withRawMode(_.size()), Right(Viewport(120, 40)))

  test("an unparseable stty size falls back to 80x24"):
    val processes = FakeProcessRunner(
      Vector(
        Script.succeeding(Vector("stty", "-g"), stdout = saved),
        Script.succeeding(Vector("stty", "raw", "-echo")),
        Script.succeeding(Vector("stty", "size"), stdout = "nonsense"),
        Script.succeeding(Vector("stty", saved)),
      ),
    )
    assertEquals(Harness(processes).terminal.withRawMode(_.size()), Right(Viewport.fallback))

  test("a failing stty size falls back to 80x24"):
    val processes = FakeProcessRunner(
      Vector(
        Script.succeeding(Vector("stty", "-g"), stdout = saved),
        Script.succeeding(Vector("stty", "raw", "-echo")),
        Script.exiting(Vector("stty", "size"), code = 1),
        Script.succeeding(Vector("stty", saved)),
      ),
    )
    assertEquals(Harness(processes).terminal.withRawMode(_.size()), Right(Viewport.fallback))

  test("keys are decoded from the input stream"):
    val harness = Harness(runner(), input = "j\u001b[A")
    assertEquals(
      harness.terminal.withRawMode(raw => Vector(raw.readKey(), raw.readKey(), raw.readKey())),
      Right(Vector(Some(PickerKey.Char('j')), Some(PickerKey.Up), None)),
    )

  test("stty missing from PATH is a raw-mode error and nothing is written"):
    val harness = Harness(FakeProcessRunner())
    assertEquals(
      harness.terminal.withRawMode(_ => ()),
      Left(TerminalError.RawModeUnavailable("stty: executable file not found in PATH")),
    )
    assertEquals(harness.written, "")

  test("a non-zero exit from stty -g is a raw-mode error with the command named"):
    val processes = FakeProcessRunner(Vector(Script.exiting(Vector("stty", "-g"), code = 1)))
    assertEquals(
      Harness(processes).terminal.withRawMode(_ => ()),
      Left(TerminalError.RawModeUnavailable("stty -g: exit status 1")),
    )

  test("a failure entering raw mode is reported and the alternate screen is never entered"):
    val processes = FakeProcessRunner(
      Vector(
        Script.succeeding(Vector("stty", "-g"), stdout = saved),
        Script.failing(Vector("stty", "raw", "-echo"), ProcessError.Failed("stty", "inappropriate ioctl")),
        Script.succeeding(Vector("stty", saved)),
      ),
    )
    val harness   = Harness(processes)
    assertEquals(
      harness.terminal.withRawMode(_ => ()),
      Left(TerminalError.RawModeUnavailable("stty: inappropriate ioctl")),
    )
    // The restore always runs once `-g` has succeeded (harmless when raw mode was never entered), but the
    // alternate-screen and cursor sequences are only ever emitted from the success branch.
    assertEquals(
      processes.calls.map(_.command),
      Vector(Vector("stty", "-g"), Vector("stty", "raw", "-echo"), Vector("stty", saved)),
    )
    assertEquals(harness.written, "")

  test("an interrupt while entering raw mode still restores the saved settings"):
    val processes = runner()
    val throwing  = ThrowingOnCommand(processes, Vector("stty", "raw", "-echo"))
    val harness   = Harness(throwing)
    // `InterruptedException` is not `NonFatal`, so munit's `intercept` will not catch it (it would rethrow
    // and fail the test instead); catch it by hand the way the rest of the codebase asserts on interrupts.
    val thrown    =
      try
        harness.terminal.withRawMode(_ => ()).discard
        None
      catch case interrupted: InterruptedException => Some(interrupted)
    assert(thrown.isDefined, "expected the InterruptedException to propagate")
    assertEquals(processes.calls.last.command, Vector("stty", saved))
    assertEquals(harness.written, "")

  test("terminal errors render with the raw-mode prefix"):
    assertEquals(
      TerminalError.RawModeUnavailable("stty -g: exit status 1").render,
      "enter raw terminal mode: stty -g: exit status 1",
    )

  test("parseSize accepts rows and columns and rejects anything else"):
    assertEquals(SttyTerminal.parseSize("24 80"), Some(Viewport(80, 24)))
    assertEquals(SttyTerminal.parseSize("0 80"), None)
    assertEquals(SttyTerminal.parseSize("24"), None)
    assertEquals(SttyTerminal.parseSize("a b"), None)

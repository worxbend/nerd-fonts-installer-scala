package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.picker.Fixtures.*
import io.worxbend.nerdfonts.releases.ReleaseError

import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

import ox.discard
import ox.sleep

/** The stderr spinner block around the release load. */
final class ReleaseLoadingSpinnerSuite extends munit.FunSuite:
  private val loadingLine = "  ⠋ Loading Nerd Fonts releases"

  /**
   * Records every `write(String)` call, in order, so a test can inspect the exact sequence sent to the
   * underlying stream instead of re-reading it after a wall-clock wait.
   */
  private class RecordingWriter extends Writer:
    private val recorded                                   = ConcurrentLinkedQueue[String]()
    override def write(text: String): Unit                 = recorded.add(text).discard
    def write(cbuf: Array[Char], off: Int, len: Int): Unit = write(String(cbuf, off, len))
    def flush(): Unit                                      = ()
    def close(): Unit                                      = ()
    def writes: Vector[String]                             = recorded.iterator().asScala.toVector
    def text: String                                       = writes.mkString

  /**
   * A `RecordingWriter` whose latch releases once `releaseAfter` writes carrying a `\r` rewrite have been
   * seen, so a test can block until the ticker has definitely painted a given number of frames without
   * guessing at a sleep long enough to cover it.
   */
  final private class LatchingWriter(releaseAfter: Int) extends RecordingWriter:
    private val rewrites                   = AtomicInteger(0)
    val latch: CountDownLatch              = CountDownLatch(1)
    override def write(text: String): Unit =
      super.write(text)
      if text.contains("\r") && rewrites.incrementAndGet() >= releaseAfter then latch.countDown()

  test("a successful load prints the brand line, ticks the spinner and ends with the loaded line"):
    val out    = LatchingWriter(releaseAfter = 2)
    val result = ReleaseLoadingSpinner.around(out, ColourMode.Plain): () =>
      assert(out.latch.await(2, TimeUnit.SECONDS), "the spinner never ticked twice")
      Right(releases)
    assertEquals(result, Right(releases))
    val text   = out.text
    assert(text.startsWith(s"\n  ✦ nerd-fonts-installer\n$loadingLine"), text)
    assert(text.contains("\r  ⠙ Loading Nerd Fonts releases"), text)
    assert(
      text.endsWith(
        "\r  ✓ Releases loaded" + " " * (loadingLine.length - "  ✓ Releases loaded".length) + "\n",
      ),
      text,
    )

  test("a failed load ends with the error message and returns it"):
    val out     = StringWriter()
    val result  = ReleaseLoadingSpinner.around(out, ColourMode.Plain)(() => Left(ReleaseError.NoReleases))
    assertEquals(result, Left(ReleaseError.NoReleases))
    val failure = "  no Nerd Fonts releases found"
    assert(
      out.toString.endsWith("\r" + failure + " " * (loadingLine.length - failure.length) + "\n"),
      out.toString,
    )

  test("plain output carries no ANSI sequences"):
    val out = StringWriter()
    ReleaseLoadingSpinner.around(out, ColourMode.Plain)(() => Right(())).discard
    assert(!out.toString.contains("\u001b["), out.toString)

  test("colour output paints the brand line"):
    val out = StringWriter()
    ReleaseLoadingSpinner.around(out, ColourMode.Ansi)(() => Right(())).discard
    assert(out.toString.contains("\u001b[38;2;"), out.toString)
    assertEquals(
      TextWidth.stripAnsi(out.toString).linesIterator.toVector.take(2),
      Vector("", "  ✦ nerd-fonts-installer"),
    )

  test("the ticker never writes after the final line"):
    // `forkDiscard` inside `supervised` guarantees the ticker is cancelled and joined before `around`
    // returns, so the last write recorded is the final line itself — asserted directly on the sequence
    // rather than by sleeping and re-reading, which only ever proved the absence of a write by luck.
    val out           = RecordingWriter()
    ReleaseLoadingSpinner.around(out, ColourMode.Plain)(() => Right(())).discard
    val expectedFinal =
      "\r" + "  ✓ Releases loaded" + " " * (loadingLine.length - "  ✓ Releases loaded".length) + "\n"
    assertEquals(out.writes.lastOption, Some(expectedFinal))

  test("an interrupted load still ends the spinner line with a newline before the exception propagates"):
    val out         = StringWriter()
    val started     = CountDownLatch(1)
    val interrupted = AtomicBoolean(false)
    val worker      = Thread(() =>
      try
        val _ = ReleaseLoadingSpinner.around(out, ColourMode.Plain): () =>
          started.countDown()
          sleep(10.seconds)
          Right(releases)
      catch case _: InterruptedException => interrupted.set(true),
    )
    worker.start()
    assert(started.await(2, TimeUnit.SECONDS), "the load never started")
    worker.interrupt()
    worker.join(2000)
    assert(interrupted.get(), "expected the interrupt to reach the load")
    assert(out.toString.endsWith("\n"), out.toString)
    assert(out.toString.contains(ReleaseLoadingSpinner.interrupted), out.toString)

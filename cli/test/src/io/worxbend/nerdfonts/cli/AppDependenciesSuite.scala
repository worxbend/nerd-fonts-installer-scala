package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallEvent
import io.worxbend.nerdfonts.install.InstallEventSink
import io.worxbend.nerdfonts.install.InstallRequest

import java.util.concurrent.atomic.AtomicReference

import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * The production wiring, exercised only through a dry run: nothing touches the network or the disk, so the
 * test proves which URL base the shipped engine would use without a single request being made.
 */
object AppDependenciesSuite extends ZIOSpecDefault:
  private val tempDir = os.Path("/tmp")

  private val request = InstallRequest(
    ReleaseSelector.Latest,
    os.Path("/fonts"),
    Vector(family("Hack")),
    RefreshFontCache.Disabled,
    DryRun.Enabled,
  )

  private def plannedUrls(variables: Map[String, String]): ZIO[Any, Throwable, Vector[String]] = ZIO.scoped:
    for
      deps   <- AppDependencies.production(environment(variables), tempDir)
      seen    = AtomicReference(Vector.empty[String])
      sink    = new InstallEventSink:
                  def emit(event: InstallEvent): Unit = event match
                    case InstallEvent.WouldInstall(_, url, _) => val _ = seen.updateAndGet(_ :+ url.value)
                    case _                                    => ()
      result <- deps.installFonts(request, sink).either
    yield
      val _ = result
      seen.get

  override def spec: Spec[TestEnvironment, Any] = suite("AppDependencies")(
    test("production builds release URLs against GitHub by default"):
      plannedUrls(Map.empty).map(urls =>
        assertTrue(
          urls == Vector("https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip"),
        ),
      )
    ,
    test("production honours the undocumented base URL test hook"):
      plannedUrls(Map(AppDependencies.baseUrlVariable -> "http://127.0.0.1:9/releases/")).map(urls =>
        assertTrue(urls == Vector("http://127.0.0.1:9/releases/latest/download/Hack.zip")),
      )
    ,
    test("a blank base URL variable keeps the GitHub base"):
      plannedUrls(Map(AppDependencies.baseUrlVariable -> "  ")).map(urls =>
        assertTrue(
          urls == Vector("https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip"),
        ),
      ),
  )

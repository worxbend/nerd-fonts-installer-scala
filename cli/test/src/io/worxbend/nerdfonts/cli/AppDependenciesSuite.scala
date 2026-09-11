package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.cli.Fakes.*
import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.install.InstallEvent
import io.worxbend.nerdfonts.install.InstallRequest

import java.util.concurrent.atomic.AtomicReference

import ox.discard

/**
 * The production wiring, exercised only through a dry run: nothing touches the network or the disk, so the
 * test proves which URL base the shipped engine would use without a single request being made.
 */
final class AppDependenciesSuite extends munit.FunSuite:
  private val tempDir = os.Path("/tmp")

  private def plannedUrls(variables: Map[String, String]): Vector[String] =
    val deps    = AppDependencies.production(environment(variables), tempDir)
    val request = InstallRequest(
      ReleaseSelector.Latest,
      os.Path("/fonts"),
      Vector(family("Hack")),
      RefreshFontCache.Disabled,
      DryRun.Enabled,
    )
    val seen    = AtomicReference(Vector.empty[String])
    val result  = deps.installFonts(
      request,
      {
        case InstallEvent.WouldInstall(_, url, _) => seen.updateAndGet(_ :+ url.value).discard
        case _                                    => ()
      },
    )
    assertEquals(result, Right(()))
    seen.get

  test("production builds release URLs against GitHub by default"):
    assertEquals(
      plannedUrls(Map.empty),
      Vector("https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip"),
    )

  test("production honours the undocumented base URL test hook"):
    assertEquals(
      plannedUrls(Map(AppDependencies.baseUrlVariable -> "http://127.0.0.1:9/releases/")),
      Vector("http://127.0.0.1:9/releases/latest/download/Hack.zip"),
    )

  test("a blank base URL variable keeps the GitHub base"):
    assertEquals(
      plannedUrls(Map(AppDependencies.baseUrlVariable -> "  ")),
      Vector("https://github.com/ryanoasis/nerd-fonts/releases/latest/download/Hack.zip"),
    )

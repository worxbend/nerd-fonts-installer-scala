package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.Url
import io.worxbend.nerdfonts.releases.ReleaseUrls
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object InstallPlanSuite extends ZIOSpecDefault:
  private val root = os.Path("/fonts")

  private def family(name: String): FamilyName = FamilyName.parse(name) match
    case Right(value) => value
    case Left(error)  => throw new AssertionError(s"unsafe $name: ${error.render}")

  private def request(selector: ReleaseSelector, names: String*): InstallRequest =
    InstallRequest(selector, root, names.toVector.map(family), RefreshFontCache.Disabled, DryRun.Disabled)

  def spec = suite("InstallPlan")(
    test("plans one download URL and one target directory per family, in request order"):
      val plan = InstallPlan.of(request(ReleaseSelector.Latest, "Hack", "JetBrainsMono"))
      assertTrue(
        plan.families == Vector(
          PlannedFamily(
            family("Hack"),
            ReleaseUrls.github.download(ReleaseSelector.Latest, family("Hack")),
            root / "Hack",
          ),
          PlannedFamily(
            family("JetBrainsMono"),
            ReleaseUrls.github.download(ReleaseSelector.Latest, family("JetBrainsMono")),
            root / "JetBrainsMono",
          ),
        ),
      )
    ,
    test("collapses duplicate families keeping the first occurrence"):
      val plan = InstallPlan.of(request(ReleaseSelector.Latest, "Z", "A", "M", "A", "Z"))
      assertTrue(plan.families.map(_.name.value) == Vector("Z", "A", "M"))
    ,
    test("a tagged selector plans the tagged download URL"):
      val tag  = ReleaseTag.parse("v3.4.0").getOrElse(throw new AssertionError("blank tag"))
      val plan = InstallPlan.of(request(ReleaseSelector.Tagged(tag), "Hack"))
      assertTrue(
        plan.families.map(_.url.value) ==
          Vector("https://github.com/ryanoasis/nerd-fonts/releases/download/v3.4.0/Hack.zip"),
      )
    ,
    test("a supplied URL base is used for every planned download"):
      val plan =
        InstallPlan.of(request(ReleaseSelector.Latest, "Hack"), ReleaseUrls(Url("http://stub.test/r")))
      assertTrue(plan.families.map(_.url.value) == Vector("http://stub.test/r/latest/download/Hack.zip"))
    ,
    test("an empty family list plans nothing"):
      assertTrue(InstallPlan.of(request(ReleaseSelector.Latest)).families == Vector.empty),
  )

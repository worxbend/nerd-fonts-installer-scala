package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.Url
import io.worxbend.nerdfonts.releases.ReleaseUrls

final class InstallPlanSuite extends munit.FunSuite:
  private val root = os.Path("/fonts")

  private def family(name: String): FamilyName = FamilyName.parse(name).getOrElse(fail(s"unsafe $name"))

  private def request(selector: ReleaseSelector, names: String*): InstallRequest =
    InstallRequest(selector, root, names.toVector.map(family), RefreshFontCache.Disabled, DryRun.Disabled)

  test("plans one download URL and one target directory per family, in request order"):
    val plan = InstallPlan.of(request(ReleaseSelector.Latest, "Hack", "JetBrainsMono"))
    assertEquals(
      plan.families,
      Vector(
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

  test("collapses duplicate families keeping the first occurrence"):
    val plan = InstallPlan.of(request(ReleaseSelector.Latest, "Z", "A", "M", "A", "Z"))
    assertEquals(plan.families.map(_.name.value), Vector("Z", "A", "M"))

  test("a tagged selector plans the tagged download URL"):
    val tag  = ReleaseTag.parse("v3.4.0").getOrElse(fail("blank tag"))
    val plan = InstallPlan.of(request(ReleaseSelector.Tagged(tag), "Hack"))
    assertEquals(
      plan.families.map(_.url.value),
      Vector("https://github.com/ryanoasis/nerd-fonts/releases/download/v3.4.0/Hack.zip"),
    )

  test("a supplied URL base is used for every planned download"):
    val plan = InstallPlan.of(request(ReleaseSelector.Latest, "Hack"), ReleaseUrls(Url("http://stub.test/r")))
    assertEquals(plan.families.map(_.url.value), Vector("http://stub.test/r/latest/download/Hack.zip"))

  test("an empty family list plans nothing"):
    assertEquals(InstallPlan.of(request(ReleaseSelector.Latest)).families, Vector.empty)

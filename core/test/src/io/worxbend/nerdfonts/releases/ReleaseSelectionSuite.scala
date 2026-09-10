package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.HttpError

final class ReleaseSelectionSuite extends munit.FunSuite:
  private def tag(value: String): ReleaseTag = ReleaseTag.parse(value).getOrElse(fail(s"blank tag $value"))

  private val newest   = Release("v3.4.0", tag("v3.4.0"), Vector("Hack"))
  private val older    = Release("v3.3.0", tag("v3.3.0"), Vector("Hack"))
  private val releases = Vector(newest, older)

  test("Latest selects the first release in catalogue order"):
    assertEquals(ReleaseSelection.select(releases, ReleaseSelector.Latest), Right(newest))

  test("Latest on an empty catalogue is NoReleases"):
    assertEquals(ReleaseSelection.select(Vector.empty, ReleaseSelector.Latest), Left(ReleaseError.NoReleases))

  test("a tag selects the release with that tag"):
    assertEquals(ReleaseSelection.select(releases, ReleaseSelector.Tagged(tag("v3.3.0"))), Right(older))

  test("an unknown tag is NotFound with the Go message"):
    val result = ReleaseSelection.select(releases, ReleaseSelector.Tagged(tag("v1.2.3")))
    assertEquals(result, Left(ReleaseError.NotFound(tag("v1.2.3"))))
    assertEquals(result.left.map(_.render), Left("nerd fonts release \"v1.2.3\" was not found"))

  test("NoReleases renders the Go message and is distinct from NotFound"):
    assertEquals(ReleaseError.NoReleases.render, "no Nerd Fonts releases found")
    assertNotEquals[ReleaseError, ReleaseError](ReleaseError.NoReleases, ReleaseError.NotFound(tag("v1.0.0")))

  test("Http and Decode errors carry the Go prefixes"):
    assertEquals(ReleaseError.Http(HttpError.Transport("boom")).render, "list Nerd Fonts releases: boom")
    assertEquals(ReleaseError.Decode("unexpected EOF").render, "decode Nerd Fonts releases: unexpected EOF")

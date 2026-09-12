package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.HttpError

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object ReleaseSelectionSuite extends ZIOSpecDefault:
  private def tag(value: String): ReleaseTag =
    ReleaseTag.parse(value).getOrElse(throw new AssertionError(s"blank tag $value"))

  private val newest   = Release("v3.4.0", tag("v3.4.0"), Vector("Hack"))
  private val older    = Release("v3.3.0", tag("v3.3.0"), Vector("Hack"))
  private val releases = Vector(newest, older)

  def spec = suite("ReleaseSelection")(
    test("Latest selects the first release in catalogue order"):
      assertTrue(ReleaseSelection.select(releases, ReleaseSelector.Latest) == Right(newest))
    ,
    test("Latest on an empty catalogue is NoReleases"):
      assertTrue(
        ReleaseSelection.select(Vector.empty, ReleaseSelector.Latest) == Left(ReleaseError.NoReleases),
      )
    ,
    test("a tag selects the release with that tag"):
      assertTrue(ReleaseSelection.select(releases, ReleaseSelector.Tagged(tag("v3.3.0"))) == Right(older))
    ,
    test("an unknown tag is NotFound with a message naming the tag"):
      val result = ReleaseSelection.select(releases, ReleaseSelector.Tagged(tag("v1.2.3")))
      assertTrue(
        result == Left(ReleaseError.NotFound(tag("v1.2.3"))),
        result.left.map(_.render) == Left("nerd fonts release \"v1.2.3\" was not found"),
      )
    ,
    test("NoReleases renders its message and is distinct from NotFound"):
      assertTrue(
        ReleaseError.NoReleases.render == "no Nerd Fonts releases found",
        ReleaseError.NoReleases != ReleaseError.NotFound(tag("v1.0.0")),
      )
    ,
    test("Http and Decode errors carry their prefixes"):
      assertTrue(
        ReleaseError.Http(HttpError.Transport("boom")).render == "list Nerd Fonts releases: boom",
        ReleaseError.Decode("unexpected EOF").render == "decode Nerd Fonts releases: unexpected EOF",
      ),
  )

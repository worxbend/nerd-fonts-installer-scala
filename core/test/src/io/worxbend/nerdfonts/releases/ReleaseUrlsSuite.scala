package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.Url

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** The Go `TestReleaseURL` / `TestChecksumURL*` tables, plus the `url.PathEscape` character classes. */
object ReleaseUrlsSuite extends ZIOSpecDefault:
  private def family(name: String): FamilyName = FamilyName.parse(name) match
    case Right(value) => value
    case Left(error)  => throw new AssertionError(s"unsafe $name: ${error.render}")

  private def tagged(tag: String): ReleaseSelector =
    ReleaseSelector.Tagged(ReleaseTag.parse(tag).getOrElse(throw new AssertionError(s"blank $tag")))

  def spec = suite("ReleaseUrls")(
    test("the latest selector uses the latest/download shape"):
      assertTrue(
        ReleaseUrls.github.download(ReleaseSelector.Latest, family("JetBrainsMono")).value ==
          "https://github.com/ryanoasis/nerd-fonts/releases/latest/download/JetBrainsMono.zip",
      )
    ,
    test("a tagged selector uses the download/<tag> shape"):
      assertTrue(
        ReleaseUrls.github.download(tagged("v3.4.0"), family("Hack")).value ==
          "https://github.com/ryanoasis/nerd-fonts/releases/download/v3.4.0/Hack.zip",
      )
    ,
    test("both path segments are percent-escaped"):
      assertTrue(
        ReleaseUrls.github.download(tagged("release candidate"), family("Symbols Nerd Font")).value ==
          "https://github.com/ryanoasis/nerd-fonts/releases/download/release%20candidate/Symbols%20Nerd%20Font.zip",
      )
    ,
    test("a download URL converts to a request URL with the same text"):
      val download = ReleaseUrls.github.download(ReleaseSelector.Latest, family("Hack"))
      assertTrue(download.url == Url(download.value))
    ,
    test("the latest checksum manifest URL"):
      assertTrue(
        ReleaseUrls.github.checksums(ReleaseSelector.Latest) ==
          Url("https://github.com/ryanoasis/nerd-fonts/releases/latest/download/SHA-256.txt"),
      )
    ,
    test("a versioned checksum manifest URL"):
      assertTrue(
        ReleaseUrls.github.checksums(tagged("v3.4.0")) ==
          Url("https://github.com/ryanoasis/nerd-fonts/releases/download/v3.4.0/SHA-256.txt"),
      )
    ,
    test("the checksum manifest URL escapes the release tag"):
      assertTrue(
        ReleaseUrls.github.checksums(tagged("release candidate")) ==
          Url("https://github.com/ryanoasis/nerd-fonts/releases/download/release%20candidate/SHA-256.txt"),
      )
    ,
    test("a custom base replaces the release page and a trailing slash is not doubled"):
      val urls = ReleaseUrls(Url("http://127.0.0.1:8080/"))
      assertTrue(
        urls
          .download(ReleaseSelector.Latest, family("Hack"))
          .value == "http://127.0.0.1:8080/latest/download/Hack.zip",
        urls.checksums(tagged("v3.4.0")) == Url("http://127.0.0.1:8080/download/v3.4.0/SHA-256.txt"),
      )
    ,
    test("PathEscape leaves unreserved characters and Go's kept delimiters alone"):
      assertTrue(PathEscape.escape("AZaz09-_.~$&+:=@") == "AZaz09-_.~$&+:=@")
    ,
    test("PathEscape encodes the segment delimiters Go encodes"):
      assertTrue(PathEscape.escape("a/b;c,d?e") == "a%2Fb%3Bc%2Cd%3Fe")
    ,
    test("PathEscape encodes spaces, asterisks and quotes with uppercase hex"):
      assertTrue(PathEscape.escape("a b*c\"d") == "a%20b%2Ac%22d")
    ,
    test("PathEscape encodes non-ASCII text byte by byte as UTF-8"):
      assertTrue(PathEscape.escape("é") == "%C3%A9"),
  )

package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.InMemoryHttpClient
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Body
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response
import io.worxbend.nerdfonts.http.Url

import scala.concurrent.duration.DurationInt

import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import zio.test.Spec
import zio.test.TestAspect
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/** Every scenario of the Go `releases_test.go`, plus the deadline and page cap the Scala port adds. */
object GitHubReleaseCatalogueSuite extends ZIOSpecDefault:
  private val base = Url("https://api.test/releases")

  private def page(number: Int): Url = Url(s"https://api.test/releases?page=$number&per_page=100")

  private def release(name: String, tag: String, assets: Vector[String], draft: Boolean = false): Json.Obj =
    Json.Obj(
      Chunk(
        "name"     -> Json.Str(name),
        "tag_name" -> Json.Str(tag),
        "draft"    -> Json.Bool(draft),
        "assets"   -> Json.Arr(Chunk.from(assets.map(asset => Json.Obj(Chunk("name" -> Json.Str(asset)))))),
      ),
    )

  private def json(entries: Json.Obj*): Response = Response.ok(Json.Arr(Chunk.from(entries)).toJson)

  private val emptyPage = json()

  private def tag(value: String): ReleaseTag =
    ReleaseTag.parse(value).getOrElse(throw AssertionError(s"blank tag $value"))

  private def catalogue(http: InMemoryHttpClient, maxPages: Int): GitHubReleaseCatalogue =
    GitHubReleaseCatalogue(http, base, maxPages)

  override def spec: Spec[TestEnvironment, Any] = suite("GitHubReleaseCatalogue")(
    test(
      "fetches and filters pages: blank names fall back to the tag, drafts and asset-less releases are dropped",
    ):
      val http     = InMemoryHttpClient(
        Map(
          page(1) -> json(
            release("", "v3.4.0", Vector("Hack.zip", "README.md")),
            release("draft", "v3.5.0", Vector("Ignored.zip"), draft = true),
          ),
          page(2) -> json(
            release("No assets", "v3.3.0", Vector.empty),
            release("v3.2.0", "v3.2.0", Vector("JetBrainsMono.zip")),
          ),
          page(3) -> emptyPage,
        ),
      )
      val expected = Vector(
        Release("v3.4.0", tag("v3.4.0"), Vector("Hack")),
        Release("v3.2.0", tag("v3.2.0"), Vector("JetBrainsMono")),
      )
      catalogue(http, maxPages = 3).releases().either.map { result =>
        assertTrue(result == Right(expected), http.requests.map(_.url) == Vector(page(1), page(2), page(3)))
      }
    ,
    test("sends the GitHub Accept header and the User-Agent on every page"):
      val http = InMemoryHttpClient(Map(page(1) -> emptyPage))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(
          result == Left(ReleaseError.NoReleases),
          http.requests.map(_.headers) ==
            Vector(Map("Accept" -> "application/vnd.github+json", "User-Agent" -> "nerd-fonts-installer")),
        )
      }
    ,
    test("continues past a page that filters to nothing"):
      val http     = InMemoryHttpClient(
        Map(
          page(1) -> json(release("draft", "v9.9.9", Vector("Hack.zip"), draft = true)),
          page(2) -> json(release("v3.4.0", "v3.4.0", Vector("Hack.zip"))),
          page(3) -> emptyPage,
        ),
      )
      val expected = Vector(Release("v3.4.0", tag("v3.4.0"), Vector("Hack")))
      catalogue(http, maxPages = 5).releases().either.map { result =>
        assertTrue(result == Right(expected), http.requests.size == 3)
      }
    ,
    test("stops at the maximum page count even when pages keep coming"):
      val full = json(release("v3.4.0", "v3.4.0", Vector("Hack.zip")))
      val http = InMemoryHttpClient(Map(page(1) -> full, page(2) -> full, page(3) -> full))
      catalogue(http, maxPages = 2).releases().either.map { result =>
        assertTrue(result.map(_.size) == Right(2), http.requests.size == 2)
      }
    ,
    test("a non-2xx page is an Http error rendered with the status line"):
      val http = InMemoryHttpClient(Map(page(1) -> Response.status(403, """{"message":"rate limited"}""")))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(
          result == Left(ReleaseError.Http(HttpError.Status(403))),
          result.left.map(_.render) == Left("list Nerd Fonts releases: 403 Forbidden"),
        )
      }
    ,
    test("malformed JSON is a Decode error with the Go prefix"):
      val http = InMemoryHttpClient(Map(page(1) -> Response.ok("[")))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(
          result.left.exists(_.isInstanceOf[ReleaseError.Decode]),
          result.left.map(_.render).left.exists(_.startsWith("decode Nerd Fonts releases: ")),
        )
      }
    ,
    test("a JSON document that is not an array is a Decode error"):
      val http = InMemoryHttpClient(Map(page(1) -> Response.ok("""{"message":"unexpected"}""")))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(result == Left(ReleaseError.Decode("expected a JSON array of releases")))
      }
    ,
    test("a field of the wrong type is a Decode error, as with Go's strict decoder"):
      val http = InMemoryHttpClient(Map(page(1) -> Response.ok("""[{"tag_name": 42}]""")))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(result == Left(ReleaseError.Decode("field tag_name: expected a string")))
      }
    ,
    test("an empty first page is NoReleases"):
      val http = InMemoryHttpClient(Map(page(1) -> emptyPage))
      catalogue(http, maxPages = 1)
        .releases()
        .either
        .map(result => assertTrue(result == Left(ReleaseError.NoReleases)))
    ,
    test("pages that all filter to nothing are NoReleases"):
      val drafts = json(release("draft", "v9.9.9", Vector("Hack.zip"), draft = true))
      val http   = InMemoryHttpClient(Map(page(1) -> drafts, page(2) -> drafts, page(3) -> emptyPage))
      catalogue(http, maxPages = 2)
        .releases()
        .either
        .map(result => assertTrue(result == Left(ReleaseError.NoReleases)))
    ,
    test("a transport failure is an Http error rendered with its cause"):
      val http = InMemoryHttpClient(Map(page(1) -> Response.transport("dial tcp: connection refused")))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(
          result.left.map(_.render) == Left("list Nerd Fonts releases: dial tcp: connection refused"),
        )
      }
    ,
    test("a page larger than the page limit is an Http error"):
      val http = InMemoryHttpClient(Map(page(1) -> json(release("v3.4.0", "v3.4.0", Vector("Hack.zip")))))
      GitHubReleaseCatalogue(http, base, maxPages = 1, pageLimit = ByteLimit.bytes(8)).releases().either.map {
        result => assertTrue(result == Left(ReleaseError.Http(HttpError.TooLarge(ByteLimit.bytes(8), None))))
      }
    ,
    test("a page reset mid-stream is an Http error carrying the reset, not an escaping exception"):
      val resetPartway = Body.failingAfter(2, "Connection reset", okByte = '['.toInt)
      val http         = InMemoryHttpClient(Map(page(1) -> Response.Served(200, Map.empty, resetPartway)))
      catalogue(http, maxPages = 1).releases().either.map { result =>
        assertTrue(result == Left(ReleaseError.Http(HttpError.Transport("Connection reset"))))
      }
    ,
    test("a page that never answers hits the per-page deadline"):
      val http =
        InMemoryHttpClient(Map(page(1) -> Response.Served(200, Map.empty, Body.blockingUntilInterrupted)))
      GitHubReleaseCatalogue(http, base, maxPages = 1, pageTimeout = 100.millis).releases().either.map {
        result =>
          assertTrue(
            result == Left(ReleaseError.Http(HttpError.Transport("timed out after 100 milliseconds"))),
          )
      }
    ,
    test("a blank tag drops the release and a missing draft field means not a draft"):
      val http = InMemoryHttpClient(
        Map(
          page(1) -> Response.ok(
            """[{"name":"x","tag_name":"  ","assets":[{"name":"A.zip"}]},{"tag_name":"v1","assets":[{"name":"B.zip"}]}]""",
          ),
          page(2) -> emptyPage,
        ),
      )
      catalogue(http, maxPages = 2).releases().either.map { result =>
        assertTrue(result == Right(Vector(Release("v1", tag("v1"), Vector("B")))))
      }
    ,
    test("families are the sorted unique zip stems, case-insensitively on the extension"):
      val assets =
        Vector("JetBrainsMono.zip", "README.md", "Hack.ZIP", "JetBrainsMono.zip", "SymbolsOnly.tar.xz")
      assertTrue(ReleasePageDecoder.familiesFromAssets(assets) == Vector("Hack", "JetBrainsMono"))
    ,
    test("page URLs append to an existing query string like Go's url.Values"):
      val url = GitHubReleaseCatalogue.pageUrl(Url("https://example.test/releases?existing=1"), 3)
      assertTrue(url == Url("https://example.test/releases?existing=1&page=3&per_page=100"))
    ,
    test("the defaults are the GitHub API, five pages, 8 MiB and 30 seconds"):
      assertTrue(
        GitHubReleaseCatalogue.defaultBaseUrl == Url(
          "https://api.github.com/repos/ryanoasis/nerd-fonts/releases",
        ),
        GitHubReleaseCatalogue.defaultMaxPages == 5,
        GitHubReleaseCatalogue.defaultPageLimit == ByteLimit.mebibytes(8),
        GitHubReleaseCatalogue.defaultPageTimeout == 30.seconds,
      )
    ,
    test("the latest keyword shared with the Go reference is `latest`"):
      assertTrue(ReleaseSelector.latestKeyword == "latest"),
  ) @@ TestAspect.withLiveClock

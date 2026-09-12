package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpClient.getString
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.Url

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import zio.Duration
import zio.IO
import zio.ZIO
import zio.json.*
import zio.json.ast.Json

/**
 * The GitHub releases API as a [[ReleaseCatalogue]], fetched one page at a time.
 *
 * Pagination stops when a raw page is empty, not when filtering emptied it: a page of only drafts or
 * asset-less releases must not hide usable releases further on. Each page has one overall deadline
 * covering connect, headers, body and decode — a single 30s budget per page; the port itself
 * only bounds connect and read separately. The page body is capped so a hostile or broken API cannot make
 * the process buffer without bound.
 */
final class GitHubReleaseCatalogue(
    http: HttpClient,
    baseUrl: Url = GitHubReleaseCatalogue.defaultBaseUrl,
    maxPages: Int = GitHubReleaseCatalogue.defaultMaxPages,
    pageLimit: ByteLimit = GitHubReleaseCatalogue.defaultPageLimit,
    pageTimeout: FiniteDuration = GitHubReleaseCatalogue.defaultPageTimeout,
) extends ReleaseCatalogue:

  def releases(): IO[ReleaseError, Vector[Release]] = collect(1, Vector.empty).flatMap: all =>
    if all.isEmpty then ZIO.fail(ReleaseError.NoReleases) else ZIO.succeed(all)

  private def collect(page: Int, collected: Vector[Release]): IO[ReleaseError, Vector[Release]] =
    if page > maxPages then ZIO.succeed(collected)
    else
      fetchPage(page).flatMap: fetched =>
        if fetched.rawCount == 0 then ZIO.succeed(collected ++ fetched.releases)
        else collect(page + 1, collected ++ fetched.releases)

  private def fetchPage(page: Int): IO[ReleaseError, ReleasePage] =
    fetchPageNow(page).timeoutFail(timedOut)(Duration.fromScala(pageTimeout))

  private def fetchPageNow(page: Int): IO[ReleaseError, ReleasePage] =
    for
      body    <- http.getString(request(page), pageLimit).mapError(ReleaseError.Http(_))
      decoded <- ZIO.fromEither(ReleasePageDecoder.decode(body))
    yield decoded

  private def request(page: Int): HttpRequest = HttpRequest(
    GitHubReleaseCatalogue.pageUrl(baseUrl, page),
    Map("Accept" -> "application/vnd.github+json", "User-Agent" -> HttpRequest.userAgent),
  )

  private def timedOut: ReleaseError = ReleaseError.Http(HttpError.Transport(s"timed out after $pageTimeout"))

object GitHubReleaseCatalogue:
  val defaultBaseUrl: Url                = Url("https://api.github.com/repos/ryanoasis/nerd-fonts/releases")
  val defaultMaxPages: Int               = 5
  val defaultPageLimit: ByteLimit        = ByteLimit.mebibytes(8)
  val defaultPageTimeout: FiniteDuration = 30.seconds
  val perPage: Int                       = 100

  /** Appends `page` and `per_page` to whatever query the base already carries. */
  private[releases] def pageUrl(base: Url, page: Int): Url =
    val separator = if base.value.contains('?') then "&" else "?"
    Url(s"${base.value}${separator}page=$page&per_page=$perPage")

/** One decoded API page: how many entries the API returned, and the usable releases among them. */
final private[releases] case class ReleasePage(rawCount: Int, releases: Vector[Release])

/**
 * Decodes one page of the releases API with a fixed set of filtering rules: drop drafts and blank tags,
 * keep only `.zip` assets as families (sorted, unique, extension stripped), drop releases with no
 * families, and fall back to the tag when the name is blank. Field type mismatches are decode errors:
 * decoding is strict.
 */
private[releases] object ReleasePageDecoder:
  private val zipSuffix = ".zip"

  def decode(body: String): Either[ReleaseError, ReleasePage] =
    for
      json   <- parse(body)
      items  <- json.asArray.map(_.toVector).toRight(malformed("expected a JSON array of releases"))
      usable <- items.foldLeft[Either[ReleaseError, Vector[Release]]](Right(Vector.empty)): (acc, item) =>
                  acc.flatMap(releases => decodeRelease(item).map(releases ++ _))
    yield ReleasePage(items.size, usable)

  private[releases] def familiesFromAssets(assets: Vector[String]): Vector[String] = assets
    .filter(_.toLowerCase.endsWith(zipSuffix))
    .map(_.dropRight(zipSuffix.length))
    .filter(_.nonEmpty)
    .distinct
    .sorted

  private def parse(body: String): Either[ReleaseError, Json] = body
    .fromJson[Json]
    .left
    .map(message => malformed(Option(message).filter(_.nonEmpty).getOrElse("invalid JSON")))

  private def decodeRelease(item: Json): Either[ReleaseError, Option[Release]] =
    for
      fields <- item.asObject.toRight(malformed("release entry is not an object"))
      draft  <- optionalBool(fields, "draft")
      tag    <- optionalString(fields, "tag_name")
      name   <- optionalString(fields, "name")
      assets <- assetNames(fields)
    yield if draft then None else ReleaseTag.parse(tag).flatMap(usableRelease(_, name, assets))

  private def usableRelease(tag: ReleaseTag, name: String, assets: Vector[String]): Option[Release] =
    Option(familiesFromAssets(assets))
      .filter(_.nonEmpty)
      .map: families =>
        Release(Option(name.trim).filter(_.nonEmpty).getOrElse(tag.value), tag, families)

  private def assetNames(fields: Json.Obj): Either[ReleaseError, Vector[String]] =
    fields.get("assets").filterNot(_ == Json.Null) match
      case None         => Right(Vector.empty)
      case Some(assets) => assets.asArray
          .toRight(malformed("field assets: expected an array"))
          .flatMap: entries =>
            entries.foldLeft[Either[ReleaseError, Vector[String]]](Right(Vector.empty)): (acc, asset) =>
              acc.flatMap(names => assetName(asset).map(names :+ _))

  private def assetName(asset: Json): Either[ReleaseError, String] =
    asset.asObject.toRight(malformed("asset entry is not an object")).flatMap(optionalString(_, "name"))

  private def optionalString(fields: Json.Obj, key: String): Either[ReleaseError, String] =
    fields.get(key).filterNot(_ == Json.Null) match
      case None        => Right("")
      case Some(value) => value.asString.toRight(malformed(s"field $key: expected a string"))

  private def optionalBool(fields: Json.Obj, key: String): Either[ReleaseError, Boolean] =
    fields.get(key).filterNot(_ == Json.Null) match
      case None        => Right(false)
      case Some(value) => value.asBoolean.toRight(malformed(s"field $key: expected a boolean"))

  private def malformed(message: String): ReleaseError = ReleaseError.Decode(message)

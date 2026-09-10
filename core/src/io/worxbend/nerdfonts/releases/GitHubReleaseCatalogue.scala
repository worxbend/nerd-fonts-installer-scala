package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.Url

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import ox.either
import ox.either.catching
import ox.either.ok
import ox.timeoutEither

/**
 * The GitHub releases API as a [[ReleaseCatalogue]], paginated exactly like the Go client.
 *
 * Pagination stops when a raw page is empty, not when filtering emptied it: a page of only drafts or
 * asset-less releases must not hide usable releases further on. Each page has one overall deadline
 * covering connect, headers, body and decode, which is Go's `http.Client{Timeout: 30s}`; the JDK client
 * itself only has a connect timeout. The page body is capped so a hostile or broken API cannot make the
 * process buffer without bound.
 */
final class GitHubReleaseCatalogue(
    http: HttpClient,
    baseUrl: Url = GitHubReleaseCatalogue.defaultBaseUrl,
    maxPages: Int = GitHubReleaseCatalogue.defaultMaxPages,
    pageLimit: ByteLimit = GitHubReleaseCatalogue.defaultPageLimit,
    pageTimeout: FiniteDuration = GitHubReleaseCatalogue.defaultPageTimeout,
) extends ReleaseCatalogue:

  def releases(): Either[ReleaseError, Vector[Release]] = collect(1, Vector.empty).flatMap: all =>
    if all.isEmpty then Left(ReleaseError.NoReleases) else Right(all)

  @tailrec
  private def collect(page: Int, collected: Vector[Release]): Either[ReleaseError, Vector[Release]] =
    if page > maxPages then Right(collected)
    else
      fetchPage(page) match
        case Left(error)                             => Left(error)
        case Right(fetched) if fetched.rawCount == 0 => Right(collected ++ fetched.releases)
        case Right(fetched)                          => collect(page + 1, collected ++ fetched.releases)

  private def fetchPage(page: Int): Either[ReleaseError, ReleasePage] =
    timeoutEither(pageTimeout, timedOut)(fetchPageNow(page))

  private def fetchPageNow(page: Int): Either[ReleaseError, ReleasePage] = either:
    val body = http.getString(request(page), pageLimit).left.map(ReleaseError.Http(_)).ok()
    ReleasePageDecoder.decode(body).ok()

  private def request(page: Int): HttpRequest = HttpRequest(
    GitHubReleaseCatalogue.pageUrl(baseUrl, page),
    Map("Accept" -> "application/vnd.github+json", "User-Agent" -> "nerd-fonts-installer"),
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
 * Decodes one page of the releases API with the Go filtering rules: drop drafts and blank tags, keep only
 * `.zip` assets as families (sorted, unique, extension stripped), drop releases with no families, and fall
 * back to the tag when the name is blank. Field type mismatches are decode errors, as with Go's strict
 * `encoding/json`.
 */
private[releases] object ReleasePageDecoder:
  private val zipSuffix = ".zip"

  def decode(body: String): Either[ReleaseError, ReleasePage] = either:
    val json   = parse(body).ok()
    val items  = json.arrOpt.map(_.toVector).toRight(malformed("expected a JSON array of releases")).ok()
    val usable = items.foldLeft[Either[ReleaseError, Vector[Release]]](Right(Vector.empty)): (acc, item) =>
      acc.flatMap(releases => decodeRelease(item).map(releases ++ _))
    ReleasePage(items.size, usable.ok())

  private[releases] def familiesFromAssets(assets: Vector[String]): Vector[String] = assets
    .filter(_.toLowerCase.endsWith(zipSuffix))
    .map(_.dropRight(zipSuffix.length))
    .filter(_.nonEmpty)
    .distinct
    .sorted

  // `trace = false`: the traced variant wraps failures in a `TraceException`, hiding the parse error type.
  private def parse(body: String): Either[ReleaseError, ujson.Value] = ujson
    .read(ujson.Readable.fromString(body), trace = false)
    .catching[ujson.ParsingFailedException]
    .left
    .map(error => malformed(Option(error.getMessage).getOrElse("invalid JSON")))

  private def decodeRelease(item: ujson.Value): Either[ReleaseError, Option[Release]] = either:
    val fields = item.objOpt.toRight(malformed("release entry is not an object")).ok()
    val draft  = optionalBool(fields, "draft").ok()
    val tag    = optionalString(fields, "tag_name").ok()
    val name   = optionalString(fields, "name").ok()
    val assets = assetNames(fields).ok()
    if draft then None else ReleaseTag.parse(tag).flatMap(usableRelease(_, name, assets))

  private def usableRelease(tag: ReleaseTag, name: String, assets: Vector[String]): Option[Release] =
    Option(familiesFromAssets(assets))
      .filter(_.nonEmpty)
      .map: families =>
        Release(Option(name.trim).filter(_.nonEmpty).getOrElse(tag.value), tag, families)

  private def assetNames(fields: ujson.Obj): Either[ReleaseError, Vector[String]] =
    fields.value.get("assets").filterNot(_.isNull) match
      case None         => Right(Vector.empty)
      case Some(assets) => assets.arrOpt
          .toRight(malformed("field assets: expected an array"))
          .flatMap: entries =>
            entries.toVector.foldLeft[Either[ReleaseError, Vector[String]]](Right(Vector.empty)):
              (acc, asset) => acc.flatMap(names => assetName(asset).map(names :+ _))

  private def assetName(asset: ujson.Value): Either[ReleaseError, String] =
    asset.objOpt.toRight(malformed("asset entry is not an object")).flatMap(optionalString(_, "name"))

  private def optionalString(fields: ujson.Obj, key: String): Either[ReleaseError, String] =
    fields.value.get(key).filterNot(_.isNull) match
      case None        => Right("")
      case Some(value) => value.strOpt.toRight(malformed(s"field $key: expected a string"))

  private def optionalBool(fields: ujson.Obj, key: String): Either[ReleaseError, Boolean] =
    fields.value.get(key).filterNot(_.isNull) match
      case None        => Right(false)
      case Some(value) => value.boolOpt.toRight(malformed(s"field $key: expected a boolean"))

  private def malformed(message: String): ReleaseError = ReleaseError.Decode(message)

package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.Diagnostics

import zio.Scope
import zio.ZIO
import zio.ZLayer
import zio.http.Client
import zio.http.ClientSSLConfig
import zio.http.DnsResolver
import zio.http.Header
import zio.http.Headers
import zio.http.Request
import zio.http.Scheme
import zio.http.URL
import zio.http.ZClient
import zio.http.netty.NettyConfig
import zio.http.netty.client.NettyClientDriver

/**
 * The production `HttpClient`, backed by zio-http/Netty.
 *
 * Two things this adapter insists on that the library does not give for free:
 *   - TLS validation. `Client.default` accepts expired and self-signed certificates in zio-http 3.11.5, so
 *     [[ZioHttpClient.hardenedClient]] builds a `Client` explicitly from the JDK's own trust store instead.
 *   - Redirects that never leak credentials cross-host, and never silently downgrade the transport. zio-http
 *     has redirects off by default (every GitHub release download redirects at least once), and its built-in
 *     `ZClientAspect.followRedirects` forwards every request header, `Authorization` included, to whatever
 *     host the redirect points at. [[followRedirects]] is a small hand-rolled loop instead, so an
 *     `Authorization` header — none is sent today, but this is the invariant that must hold if one ever is —
 *     is dropped the moment the redirect target's scheme, host or port differs from the original request's,
 *     and an `https` response may never redirect to plain `http`. The replaced JDK client got the latter for
 *     free from `Redirect.NORMAL`; nothing in zio-http provides it, so it is enforced here.
 */
final class ZioHttpClient(client: Client) extends HttpClient:
  def get(
      request: HttpRequest,
      limit: ByteLimit,
      overflow: Overflow = Overflow.Reject,
  ): ZIO[Scope, HttpError, HttpResponse] =
    for
      url       <- ZIO.fromEither(URL.decode(request.url.value)).mapError(transportError)
      response  <-
        followRedirects(Request.get(url).addHeaders(headersFor(request)), ZioHttpClient.maxRedirects)
          .mapError(transportError)
      delivered <- ResponseDelivery.deliver(
                     response.status.code,
                     contentLength(response),
                     limit,
                     overflow,
                     response.body.asStream,
                   )
    yield delivered

  // `ZClient.streaming`, not the deprecated instance `request`/`batched`: `batched` materialises the whole
  // body in memory before this method even returns, which a 768 MiB font archive must never do.
  private def followRedirects(request: Request, remaining: Int): ZIO[Scope, Throwable, zio.http.Response] =
    ZClient
      .streaming(request)
      .provideSomeLayer[Scope](ZLayer.succeed(client))
      .flatMap: response =>
        if response.status.isRedirection && remaining > 0 then
          response.header(Header.Location) match
            case Some(Header.Location(location)) => request.url.resolve(location) match
                case Right(next) if ZioHttpClient.downgrades(request.url, next) =>
                  ZIO.fail(ZioHttpClient.DowngradedRedirect(request.url, next))
                case Right(next)                                                => followRedirects(
                    request.copy(url = next, headers = headersForRedirect(request, next)),
                    remaining - 1,
                  )
                case Left(_)                                                    => ZIO.succeed(response)
            case _                               => ZIO.succeed(response)
        else ZIO.succeed(response)

  // Keyed on the whole origin, not the host alone: a same-host `https` -> `http` hop is a different origin
  // and must not carry credentials either.
  private def headersForRedirect(request: Request, next: URL): Headers =
    if ZioHttpClient.sameOrigin(request.url, next) then request.headers
    else request.headers.removeHeader(Header.Authorization)

  private def headersFor(request: HttpRequest): Headers =
    val merged = Map("User-Agent" -> HttpRequest.userAgent) ++ request.headers
    Headers(merged.toSeq.map((name, value) => Header.Custom(name, value)))

  private def contentLength(response: zio.http.Response): Option[Long] =
    response.header(Header.ContentLength).collect { case Header.ContentLength(length) => length }

  private def transportError(cause: Throwable): HttpError = HttpError.Transport(Diagnostics.describe(cause))

object ZioHttpClient:
  private val maxRedirects: Int = 5

  /**
   * An `https` response may never send us to plain `http`. The replaced JDK client got this from
   * `Redirect.NORMAL`; zio-http offers no equivalent, so it is enforced here. Exposed to the tests because
   * proving it end to end would need a TLS loopback server whose certificate this client would — correctly —
   * refuse.
   */
  private[http] def downgrades(from: URL, to: URL): Boolean = isSecure(from) && !isSecure(to)

  /** Credentials travel only within one origin; a scheme or port change is a different origin than the host alone. */
  private[http] def sameOrigin(from: URL, to: URL): Boolean =
    from.host == to.host && from.scheme == to.scheme && from.port == to.port

  private def isSecure(url: URL): Boolean = url.scheme.contains(Scheme.HTTPS)

  /**
   * Raised when a secure response redirects to plain `http`. Carried as a `Throwable` so it travels the same
   * path as any other transport problem and surfaces as `HttpError.Transport`; `Diagnostics.describe` reads
   * `getMessage`, so the message is the whole user-visible text.
   */
  final private class DowngradedRedirect(from: URL, to: URL)
      extends RuntimeException(
        s"refusing to follow a redirect from ${scheme(from)} to ${scheme(to)}: ${to.encode}",
      )

  private def scheme(url: URL): String = url.scheme.map(_.encode).getOrElse("unknown")

  /**
   * The TLS-hardened `Client` layer: `ClientSSLConfig.FromJavaxNetSsl()` uses the JDK's default trust
   * manager, which — unlike `Client.default` — actually rejects expired and self-signed certificates.
   */
  val hardenedClient: ZLayer[Any, Throwable, Client] = ZLayer.make[Client](
    ZLayer.succeed(ZClient.Config.default.ssl(ClientSSLConfig.FromJavaxNetSsl())),
    ZClient.customized,
    NettyClientDriver.live,
    ZLayer.succeed(NettyConfig.default),
    DnsResolver.default,
  )

  /** The `HttpClient` port, wired onto whatever `Client` is in the environment. */
  val layer: ZLayer[Client, Nothing, HttpClient] = ZLayer.fromFunction(ZioHttpClient(_))

  /** The hardened client wired straight through to the port; the only layer a production run needs. */
  val live: ZLayer[Any, Throwable, HttpClient] = hardenedClient >>> layer

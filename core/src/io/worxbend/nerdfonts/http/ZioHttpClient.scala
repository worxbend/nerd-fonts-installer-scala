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
 *   - Redirects that never leak credentials cross-host. zio-http has redirects off by default (every GitHub
 *     release download redirects at least once), and its built-in `ZClientAspect.followRedirects` forwards
 *     every request header, `Authorization` included, to whatever host the redirect points at. [[followRedirects]]
 *     is a small hand-rolled loop instead, so an `Authorization` header — none is sent today, but this is the
 *     invariant that must hold if one ever is — is dropped the moment the redirect target's host differs
 *     from the original request's.
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
                case Right(next) => followRedirects(
                    request.copy(url = next, headers = headersForRedirect(request, next)),
                    remaining - 1,
                  )
                case Left(_)     => ZIO.succeed(response)
            case _                               => ZIO.succeed(response)
        else ZIO.succeed(response)

  private def headersForRedirect(request: Request, next: URL): Headers =
    if request.url.host == next.host then request.headers
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

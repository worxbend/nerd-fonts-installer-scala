package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.Diagnostics

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import scala.annotation.tailrec
import scala.jdk.OptionConverters.*
import scala.util.Try

import ox.either.catching

/**
 * The production `HttpClient` over `java.net.http`.
 *
 * Only a connect timeout is configured: an overall request timeout would cap a legitimately slow 700 MiB
 * download, so callers own their deadlines with `timeoutEither` instead. Redirects follow GitHub's
 * `releases/download` → object storage hop (`Redirect.NORMAL` refuses https → http downgrades).
 */
final class JdkHttpClient private (client: JavaHttpClient) extends HttpClient:
  def get[A](request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject)(
      consume: InputStream => A,
  ): Either[HttpError, A] = send(request).flatMap(ResponseDelivery.deliver(_, limit, overflow)(consume))

  private def send(request: HttpRequest): Either[HttpError, RawResponse] = build(request).flatMap:
    javaRequest =>
      client.send(javaRequest, HttpResponse.BodyHandlers.ofInputStream()).catching[IOException] match
        case Right(response) =>
          Right(RawResponse(response.statusCode(), contentLength(response), response.body()))
        case Left(error)     => Left(transportFailure(error))

  // A malformed URL or header is a defect in a URL builder, reported as a transport failure rather than
  // thrown, so a bad release tag can never crash the process.
  private def build(request: HttpRequest): Either[HttpError, JavaHttpRequest] = Try:
    val builder = JavaHttpRequest
      .newBuilder(URI.create(request.url.value))
      .GET()
      .setHeader("User-Agent", HttpRequest.userAgent)
    request.headers.foreach((name, value) => builder.setHeader(name, value))
    builder.build()
  .toEither.left.map(error => HttpError.Transport(Diagnostics.describe(error)))

  private def contentLength(response: HttpResponse[InputStream]): Option[Long] =
    response.headers().firstValueAsLong("Content-Length").toScala.map(_.longValue)

  // `HttpClient.send` reports an interrupt that lands mid-transfer as an `IOException` wrapping the
  // `InterruptedException`, with the interrupt flag cleared. The flag is re-asserted so the enclosing Ox
  // scope still observes the cancellation once this `Left` has been reported.
  private def transportFailure(error: IOException): HttpError =
    if JdkHttpClient.wrapsInterrupt(error) then Thread.currentThread().interrupt()
    HttpError.Transport(Diagnostics.describe(error))

object JdkHttpClient:
  val connectTimeout: Duration = Duration.ofSeconds(30)

  def apply(): JdkHttpClient = new JdkHttpClient(
    JavaHttpClient
      .newBuilder()
      .connectTimeout(connectTimeout)
      .followRedirects(JavaHttpClient.Redirect.NORMAL)
      .build(),
  )

  // `private[http]`, not `private`, so `JdkHttpClientSuite` can test the cause-chain walk directly: whether an
  // interrupted `client.send` throws a bare `InterruptedException` or an `IOException` wrapping one is a
  // JDK-internal timing detail that a test cannot reliably force over a real connection.
  @tailrec
  private[http] def wrapsInterrupt(error: Throwable): Boolean = Option(error.getCause) match
    case None                          => false
    case Some(_: InterruptedException) => true
    case Some(cause)                   => wrapsInterrupt(cause)

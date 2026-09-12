package io.worxbend.nerdfonts.http

import zio.Scope
import zio.ZIO
import zio.stream.ZStream

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * An `HttpClient` serving canned responses by URL, for tests in every module.
 *
 * Bodies go through [[ResponseDelivery]], so a test that asserts on the byte caps exercises the production
 * cap logic rather than a re-implementation. Every request is recorded so tests can assert on headers,
 * order and count. A URL with no route is a transport failure, which is what a test that must not touch
 * the network should see.
 *
 * Requests are recorded on a plain `AtomicReference`, not a `Ref`, so a test can read `requests` outside
 * any `ZIO` effect the same way it reads any other in-memory test double.
 */
final class InMemoryHttpClient(routes: Map[Url, InMemoryHttpClient.Response]) extends HttpClient:
  private val recorded = AtomicReference(Vector.empty[HttpRequest])

  /** Every request seen so far, in call order (safe to read while other fibers are still calling). */
  def requests: Vector[HttpRequest] = recorded.get()

  def get(
      request: HttpRequest,
      limit: ByteLimit,
      overflow: Overflow = Overflow.Reject,
  ): ZIO[Scope, HttpError, HttpResponse] =
    ZIO.succeed(recorded.updateAndGet(_ :+ request)) *> (routes.get(request.url) match
      case None                                                            => ZIO.fail(HttpError.Transport(s"no route for ${request.url.value}"))
      case Some(InMemoryHttpClient.Response.Failed(cause))                 => ZIO.fail(HttpError.Transport(cause))
      case Some(InMemoryHttpClient.Response.Served(status, headers, body)) => ResponseDelivery.deliver(
          status,
          InMemoryHttpClient.contentLength(headers),
          limit,
          overflow,
          // Closed on exhaustion, failure or interruption, exactly like the production adapter's connection,
          // so a test asserting on that guarantee exercises the same lifecycle as the real client.
          ZStream.fromInputStreamZIO(ZIO.attempt(body.open()).refineToOrDie[IOException]),
        ))

object InMemoryHttpClient:
  /** The body a route serves; `Streamed` opens a fresh stream per request, e.g. one that blocks until interrupted. */
  enum Body:
    case Bytes(content: Array[Byte])
    case Streamed(openStream: () => InputStream)

    def open(): InputStream = this match
      case Bytes(content)       => ByteArrayInputStream(content)
      case Streamed(openStream) => openStream()

  object Body:
    def text(value: String): Body = Bytes(value.getBytes(StandardCharsets.UTF_8))

    /** A body whose first read parks until the reading thread is interrupted, for cancellation tests. */
    def blockingUntilInterrupted: Body = Streamed(() => BlockingInputStream())

    /**
     * A body that serves `okByte` `count` times, then throws an `IOException(message)` on every further read
     * — a reset mid-stream, after headers were already accepted.
     */
    def failingAfter(count: Int, message: String, okByte: Int = 'x'.toInt): Body = Streamed: () =>
      new InputStream:
        private val served       = AtomicInteger(0)
        override def read(): Int =
          if served.getAndIncrement() < count then okByte else throw IOException(message)

  /** What a route answers: a served response or a transport-level failure. */
  enum Response:
    case Served(status: Int, headers: Map[String, String] = Map.empty, body: Body = Body.Bytes(Array.empty))
    case Failed(cause: String)

  object Response:
    def ok(text: String): Response = Served(200, Map.empty, Body.text(text))

    def ok(bytes: Array[Byte]): Response = Served(200, Map.empty, Body.Bytes(bytes))

    /** A 200 whose `Content-Length` header is set independently of the body actually served. */
    def okDeclaring(contentLength: Long, bytes: Array[Byte]): Response =
      Served(200, Map("Content-Length" -> contentLength.toString), Body.Bytes(bytes))

    def status(code: Int, body: String = ""): Response = Served(code, Map.empty, Body.text(body))

    def transport(cause: String): Response = Failed(cause)

  private def contentLength(headers: Map[String, String]): Option[Long] = headers
    .collectFirst { case (name, value) if name.equalsIgnoreCase("Content-Length") => value }
    .flatMap(_.toLongOption)

  final private class BlockingInputStream extends InputStream:
    override def read(): Int =
      CountDownLatch(1).await()
      -1

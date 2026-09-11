package io.worxbend.nerdfonts.http

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import ox.discard

/**
 * An `HttpClient` serving canned responses by URL, for tests in every module.
 *
 * Bodies go through [[ResponseDelivery]] and therefore through the real [[BoundedInputStream]], so a test
 * that asserts on the byte caps exercises the production logic rather than a re-implementation. Every
 * request is recorded so tests can assert on headers, order and count. A URL with no route is a transport
 * failure, which is what a test that must not touch the network should see.
 */
final class InMemoryHttpClient(routes: Map[Url, InMemoryHttpClient.Response]) extends HttpClient:
  private val recorded = AtomicReference(Vector.empty[HttpRequest])

  /** Every request seen so far, in call order (safe to read while other threads are still calling). */
  def requests: Vector[HttpRequest] = recorded.get()

  def get[A](request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject)(
      consume: InputStream => A,
  ): Either[HttpError, A] =
    recorded.updateAndGet(_ :+ request).discard
    routes.get(request.url) match
      case None                                                            => Left(HttpError.Transport(s"no route for ${request.url.value}"))
      case Some(InMemoryHttpClient.Response.Failed(cause))                 => Left(HttpError.Transport(cause))
      case Some(InMemoryHttpClient.Response.Served(status, headers, body)) =>
        val raw = RawResponse(status, InMemoryHttpClient.contentLength(headers), body.open())
        ResponseDelivery.deliver(raw, limit, overflow)(consume)

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

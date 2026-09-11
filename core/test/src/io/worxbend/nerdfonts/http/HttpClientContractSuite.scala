package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Try

/**
 * The `HttpClient` contract, exercised through the in-memory fake. The fake delegates to the same
 * `ResponseDelivery` as the JDK adapter, so these assertions hold for production as well. Anything true only
 * of `InMemoryHttpClient` itself (its route map, its request log, how it opens a streamed body) belongs in
 * [[InMemoryHttpClientSuite]] instead, so this suite's claim stays accurate.
 */
final class HttpClientContractSuite extends munit.FunSuite:
  private val url   = Url("https://example.test/asset.zip")
  private val limit = ByteLimit.bytes(8)

  private def client(response: Response): InMemoryHttpClient = InMemoryHttpClient(Map(url -> response))

  private def countingConsume(calls: AtomicInteger): InputStream => Int = body =>
    calls.incrementAndGet()
    body.readAllBytes().length

  test("a non-2xx status is rejected before consume runs"):
    val calls  = AtomicInteger(0)
    val result = client(Response.status(404, "missing")).get(HttpRequest(url), limit)(countingConsume(calls))
    assertEquals(result, Left(HttpError.Status(404)))
    assertEquals(calls.get(), 0)

  test("a 3xx status is rejected like any other non-success"):
    val result = client(Response.status(302)).get(HttpRequest(url), limit)(_ => ())
    assertEquals(result, Left(HttpError.Status(302)))

  test("a Content-Length above the limit is rejected before consume runs"):
    val calls    = AtomicInteger(0)
    val response = Response.okDeclaring(contentLength = 9, bytes = Array.ofDim[Byte](2))
    val result   = client(response).get(HttpRequest(url), limit)(countingConsume(calls))
    assertEquals(result, Left(HttpError.TooLarge(limit, Some(9L))))
    assertEquals(calls.get(), 0)

  test("a body of limit + 1 bytes without Content-Length is TooLarge even though consume returned"):
    val calls  = AtomicInteger(0)
    val result =
      client(Response.ok(Array.ofDim[Byte](9))).get(HttpRequest(url), limit)(countingConsume(calls))
    assertEquals(result, Left(HttpError.TooLarge(limit, None)))
    assertEquals(calls.get(), 1)

  test("a body of exactly the limit is delivered whole"):
    val result =
      client(Response.ok(Array.ofDim[Byte](8))).get(HttpRequest(url), limit)(_.readAllBytes().length)
    assertEquals(result, Right(8))

  test("truncate mode returns the first limit bytes of a longer body"):
    val result = client(Response.ok("0123456789")).getString(HttpRequest(url), limit, Overflow.Truncate)
    assertEquals(result, Right("01234567"))

  test("the body is closed when consume returns"):
    val closed = AtomicBoolean(false)
    val result =
      client(Response.Served(200, Map.empty, closable(closed))).get(HttpRequest(url), limit)(_ => "done")
    assertEquals(result, Right("done"))
    assert(closed.get())

  test("the body is closed when consume throws, and the exception propagates"):
    val closed = AtomicBoolean(false)
    val thrown = Try(client(Response.Served(200, Map.empty, closable(closed))).get(HttpRequest(url), limit):
      _ => throw IllegalStateException("copy failed"))
    assert(thrown.isFailure)
    assert(closed.get())

  test("a transport failure is reported as such"):
    val result = client(Response.transport("connection reset")).get(HttpRequest(url), limit)(_ => ())
    assertEquals(result, Left(HttpError.Transport("connection reset")))

  test("getString decodes the body as UTF-8"):
    val result = client(Response.ok("héllo")).getString(HttpRequest(url), ByteLimit.bytes(64))
    assertEquals(result, Right("héllo"))

  test("getString reports a mid-body IOException as Transport instead of letting it escape"):
    val failingAfterAFewBytes = InMemoryHttpClient.Body.failingAfter(3, "Connection reset")
    val result                =
      client(Response.Served(200, Map.empty, failingAfterAFewBytes)).getString(HttpRequest(url), limit)
    assertEquals(result, Left(HttpError.Transport("Connection reset")))

  private def closable(closed: AtomicBoolean): InMemoryHttpClient.Body = InMemoryHttpClient.Body.Streamed:
    () =>
      new java.io.ByteArrayInputStream("payload".getBytes):
        override def close(): Unit = closed.set(true)

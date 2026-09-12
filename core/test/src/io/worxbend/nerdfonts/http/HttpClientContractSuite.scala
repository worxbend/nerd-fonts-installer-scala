package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.http.HttpClient.getBytes
import io.worxbend.nerdfonts.http.HttpClient.getString
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response

import zio.IO
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `HttpClient` contract, exercised through the in-memory fake. The fake delegates to the same
 * `ResponseDelivery` as the production adapter, so these assertions hold there as well. Anything true only
 * of `InMemoryHttpClient` itself (its route map, its request log, how it opens a streamed body) belongs in
 * [[InMemoryHttpClientSuite]] instead, so this suite's claim stays accurate.
 */
object HttpClientContractSuite extends ZIOSpecDefault:
  private val url   = Url("https://example.test/asset.zip")
  private val limit = ByteLimit.bytes(8)

  private def client(response: Response): InMemoryHttpClient = InMemoryHttpClient(Map(url -> response))

  private def collect(response: Response, overflow: Overflow = Overflow.Reject): IO[HttpError, Int] =
    client(response).getBytes(HttpRequest(url), limit, overflow).map(_.length)

  private def closable(closed: AtomicBoolean, bytes: Array[Byte]): InMemoryHttpClient.Body =
    InMemoryHttpClient.Body.Streamed: () =>
      new ByteArrayInputStream(bytes):
        override def close(): Unit = closed.set(true)

  override def spec: Spec[TestEnvironment, Any] = suite("HttpClient contract")(
    test("a non-2xx status is rejected before the body is ever read"):
      collect(Response.status(404, "missing")).either.map(result =>
        assertTrue(result == Left(HttpError.Status(404))),
      )
    ,
    test("a 3xx status is rejected like any other non-success"):
      collect(Response.status(302)).either.map(result => assertTrue(result == Left(HttpError.Status(302))))
    ,
    test("a Content-Length above the limit is rejected before the body is ever read"):
      val response = Response.okDeclaring(contentLength = 9, bytes = Array.ofDim[Byte](2))
      collect(response).either.map(result => assertTrue(result == Left(HttpError.TooLarge(limit, Some(9L)))))
    ,
    test("a body of limit + 1 bytes without Content-Length is TooLarge even though it was fully read"):
      collect(Response.ok(Array.ofDim[Byte](9))).either.map(result =>
        assertTrue(result == Left(HttpError.TooLarge(limit, None))),
      )
    ,
    test("a body of exactly the limit is delivered whole"):
      collect(Response.ok(Array.ofDim[Byte](8))).map(length => assertTrue(length == 8))
    ,
    test("truncate mode returns the first limit bytes of a longer body"):
      client(Response.ok("0123456789"))
        .getString(HttpRequest(url), limit, Overflow.Truncate)
        .map(text => assertTrue(text == "01234567"))
    ,
    test("the body is closed once it is fully consumed"):
      val closed = AtomicBoolean(false)
      client(Response.Served(200, Map.empty, closable(closed, "payload".getBytes)))
        .getBytes(HttpRequest(url), limit)
        .map(_ => assertTrue(closed.get()))
    ,
    test("the body is closed even when it fails the byte cap"):
      val closed = AtomicBoolean(false)
      client(Response.Served(200, Map.empty, closable(closed, Array.ofDim[Byte](20))))
        .getBytes(HttpRequest(url), limit)
        .either
        .map(result => assertTrue(result.isLeft, closed.get()))
    ,
    test("the body is closed even when the caller's own processing fails after the response is delivered"):
      val closed = AtomicBoolean(false)
      ZIO
        .scoped(
          client(Response.Served(200, Map.empty, closable(closed, "payload".getBytes)))
            .get(HttpRequest(url), limit)
            .flatMap(_.body.runForeachChunk(_ => ZIO.fail(HttpError.Transport("boom")))),
        )
        .either
        .map(result => assertTrue(result.isLeft, closed.get()))
    ,
    test("a transport failure is reported as such"):
      collect(Response.transport("connection reset")).either.map(result =>
        assertTrue(result == Left(HttpError.Transport("connection reset"))),
      )
    ,
    test("getString decodes the body as UTF-8"):
      client(Response.ok("héllo"))
        .getString(HttpRequest(url), ByteLimit.bytes(64))
        .map(text => assertTrue(text == "héllo"))
    ,
    test("getString reports a mid-body IOException as Transport instead of letting it escape"):
      val failingAfterAFewBytes = InMemoryHttpClient.Body.failingAfter(3, "Connection reset")
      client(Response.Served(200, Map.empty, failingAfterAFewBytes))
        .getString(HttpRequest(url), limit)
        .either
        .map(result => assertTrue(result == Left(HttpError.Transport("Connection reset")))),
  )

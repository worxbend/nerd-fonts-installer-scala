package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.http.HttpClient.getBytes
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Body
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response

import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behaviour that belongs to the fake itself, not to the `HttpClient` contract every adapter shares: how an
 * unrouted URL is reported, that every request is recorded, and that a `Body.Streamed` route opens a fresh
 * stream per call. None of this holds for `ZioHttpClient`, which has no route map to consult or requests to
 * record.
 */
object InMemoryHttpClientSuite extends ZIOSpecDefault:
  private val url   = Url("https://example.test/asset.zip")
  private val limit = ByteLimit.bytes(8)

  override def spec: Spec[TestEnvironment, Any] = suite("InMemoryHttpClient")(
    test("an unrouted URL is a transport failure rather than a network call"):
      InMemoryHttpClient(Map.empty)
        .getBytes(HttpRequest(url), limit)
        .either
        .map(result => assertTrue(result == Left(HttpError.Transport(s"no route for ${url.value}"))))
    ,
    test("every request is recorded with its headers"):
      val http    = InMemoryHttpClient(Map(url -> Response.ok("x")))
      val request = HttpRequest(url, Map("Accept" -> "text/plain"))
      http
        .getBytes(request, limit)
        .map(bytes => assertTrue(bytes.toArray.sameElements("x".getBytes), http.requests == Vector(request)))
    ,
    test("a streamed body opens a fresh stream per request"):
      val opened = AtomicInteger(0)
      val http   = InMemoryHttpClient(
        Map(
          url -> Response.Served(
            200,
            Map.empty,
            Body.Streamed(() =>
              opened.incrementAndGet(); ByteArrayInputStream(Array.emptyByteArray),
            ),
          ),
        ),
      )
      for
        first  <- http.getBytes(HttpRequest(url), limit)
        second <- http.getBytes(HttpRequest(url), limit)
      yield assertTrue(first.isEmpty, second.isEmpty, opened.get() == 2),
  )

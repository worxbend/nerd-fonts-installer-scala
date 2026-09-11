package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.http.InMemoryHttpClient.Body
import io.worxbend.nerdfonts.http.InMemoryHttpClient.Response

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behaviour that belongs to the fake itself, not to the `HttpClient` contract every adapter shares: how an
 * unrouted URL is reported, that every request is recorded, and that a `Body.Streamed` route opens a fresh
 * stream per call. None of this holds for `JdkHttpClient`, which has no route map to consult or requests to
 * record.
 */
final class InMemoryHttpClientSuite extends munit.FunSuite:
  private val url   = Url("https://example.test/asset.zip")
  private val limit = ByteLimit.bytes(8)

  test("an unrouted URL is a transport failure rather than a network call"):
    val result = InMemoryHttpClient(Map.empty).get(HttpRequest(url), limit)(_ => ())
    assertEquals(result, Left(HttpError.Transport(s"no route for ${url.value}")))

  test("every request is recorded with its headers"):
    val http    = InMemoryHttpClient(Map(url -> Response.ok("x")))
    val request = HttpRequest(url, Map("Accept" -> "text/plain"))
    assertEquals(http.get(request, limit)(_ => ()), Right(()))
    assertEquals(http.requests, Vector(request))

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
    assertEquals(http.get(HttpRequest(url), limit)(_ => ()), Right(()))
    assertEquals(http.get(HttpRequest(url), limit)(_ => ()), Right(()))
    assertEquals(opened.get(), 2)

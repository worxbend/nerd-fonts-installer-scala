package io.worxbend.nerdfonts.http

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

import scala.util.Using

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * The JDK adapter against a loopback `HttpServer`: no network leaves the machine, but the real
 * `java.net.http` stack, its header handling and its exceptions are exercised.
 */
final class JdkHttpClientSuite extends munit.FunSuite:
  private val limit = ByteLimit.bytes(64)

  final private case class Served(status: Int, body: String, contentLength: Option[Long] = None)

  private def withServer[A](respond: HttpExchange => Served)(test: Url => A): A =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange =>
        val served = respond(exchange)
        val bytes  = served.body.getBytes
        served.contentLength.foreach(length =>
          exchange.getResponseHeaders.set("Content-Length", length.toString),
        )
        exchange.sendResponseHeaders(served.status, if bytes.isEmpty then -1 else bytes.length.toLong)
        Using.resource(exchange.getResponseBody)(_.write(bytes)),
    )
    server.start()
    try test(Url(s"http://127.0.0.1:${server.getAddress.getPort}/asset"))
    finally server.stop(0)

  test("a 200 body is delivered and decoded"):
    withServer(_ => Served(200, "hello")): url =>
      assertEquals(JdkHttpClient().getString(HttpRequest(url), limit), Right("hello"))

  test("the default User-Agent is sent and request headers are forwarded"):
    val seen                            = AtomicReference(Map.empty[String, String])
    val respond: HttpExchange => Served = exchange =>
      seen.set(
        Map(
          "User-Agent" -> exchange.getRequestHeaders.getFirst("User-Agent"),
          "Accept"     -> exchange.getRequestHeaders.getFirst("Accept"),
        ),
      )
      Served(200, "ok")
    withServer(respond): url =>
      val request = HttpRequest(url, Map("Accept" -> "application/vnd.github+json"))
      assertEquals(JdkHttpClient().getString(request, limit), Right("ok"))
      assertEquals(
        seen.get(),
        Map("User-Agent" -> "nerd-fonts-installer", "Accept" -> "application/vnd.github+json"),
      )

  test("a non-2xx response is a Status error carrying the code"):
    withServer(_ => Served(404, "nope")): url =>
      assertEquals(JdkHttpClient().getString(HttpRequest(url), limit), Left(HttpError.Status(404)))

  test("a Content-Length above the limit is rejected with the declared size"):
    val body = "x" * 100
    withServer(_ => Served(200, body, Some(body.length.toLong))): url =>
      assertEquals(
        JdkHttpClient().getString(HttpRequest(url), limit),
        Left(HttpError.TooLarge(limit, Some(100L))),
      )

  test("a refused connection is a Transport error"):
    val port   = Using.resource(ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress))(_.getLocalPort)
    val result = JdkHttpClient().getString(HttpRequest(Url(s"http://127.0.0.1:$port/")), limit)
    assert(result.left.exists(_.isInstanceOf[HttpError.Transport]), result.toString)

  test("a malformed URL is a Transport error rather than an exception"):
    val result = JdkHttpClient().getString(HttpRequest(Url("not a url")), limit)
    assert(result.left.exists(_.isInstanceOf[HttpError.Transport]), result.toString)

  // Invariant 5: `transportFailure` re-asserts the interrupt flag when `client.send` reports an interrupt as
  // an `IOException` wrapping it, so an enclosing Ox scope still observes the cancellation. Driving this over
  // a real loopback connection is not reliable (whether an interrupted `send` throws a bare
  // `InterruptedException` or an `IOException` wrapping one is a JDK-internal timing detail, not something a
  // test can force), so the cause-chain walk `wrapsInterrupt` itself is tested directly instead.
  test("wrapsInterrupt finds an InterruptedException anywhere in the cause chain"):
    assert(JdkHttpClient.wrapsInterrupt(IOException("reset", InterruptedException())))
    assert(JdkHttpClient.wrapsInterrupt(IOException("reset", IOException("nested", InterruptedException()))))

  test("wrapsInterrupt is false when nothing in the chain is an InterruptedException"):
    assert(!JdkHttpClient.wrapsInterrupt(IOException("reset")))
    assert(!JdkHttpClient.wrapsInterrupt(IOException("reset", RuntimeException("boom"))))

package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.http.HttpClient.getString

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

import scala.util.Using

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import zio.IO
import zio.ZIO
import zio.test.Spec
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

/**
 * `ZioHttpClient` against a loopback `HttpServer`: no network leaves the machine, but the real zio-http /
 * Netty stack, its header handling and its exceptions are exercised.
 *
 * The old JDK adapter's `wrapsInterrupt` tests have no equivalent here: that was a workaround for
 * `java.net.http`'s habit of reporting a `Thread.interrupt()` as a plain `IOException`, which the
 * `HttpClient`'s caller had to unwrap by hand to keep interruption working. zio-http's Netty transport is
 * driven by ZIO's own fiber interruption, so there is no such wrapping left to unit test.
 */
object ZioHttpClientSuite extends ZIOSpecDefault:
  private val limit = ByteLimit.bytes(64)

  final private case class Served(status: Int, body: String, contentLength: Option[Long] = None)

  private def withServer[A](respond: HttpExchange => Served)(
      test: (HttpClient, Url) => IO[Nothing, A],
  ): ZIO[HttpClient, Nothing, A] = ZIO.acquireReleaseWith(ZIO.succeed(startServer(respond)))(server =>
    ZIO.succeed(server.stop(0)),
  ): server =>
    ZIO.serviceWithZIO[HttpClient]: client =>
      test(client, Url(s"http://127.0.0.1:${server.getAddress.getPort}/asset"))

  private def startServer(respond: HttpExchange => Served): HttpServer =
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
    server

  override def spec: Spec[TestEnvironment, Any] = suite("ZioHttpClient")(
    test("a 200 body is delivered and decoded"):
      withServer(_ => Served(200, "hello")): (client, url) =>
        client.getString(HttpRequest(url), limit).either.map(result => assertTrue(result == Right("hello")))
    ,
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
      withServer(respond): (client, url) =>
        val request = HttpRequest(url, Map("Accept" -> "application/vnd.github+json"))
        client.getString(request, limit).either.map { result =>
          assertTrue(
            result == Right("ok"),
            seen
              .get() == Map("User-Agent" -> "nerd-fonts-installer", "Accept" -> "application/vnd.github+json"),
          )
        }
    ,
    test("a non-2xx response is a Status error carrying the code"):
      withServer(_ => Served(404, "nope")): (client, url) =>
        client
          .getString(HttpRequest(url), limit)
          .either
          .map(result => assertTrue(result == Left(HttpError.Status(404))))
    ,
    test("a Content-Length above the limit is rejected with the declared size"):
      val body = "x" * 100
      withServer(_ => Served(200, body, Some(body.length.toLong))): (client, url) =>
        client
          .getString(HttpRequest(url), limit)
          .either
          .map(result => assertTrue(result == Left(HttpError.TooLarge(limit, Some(100L)))))
    ,
    test("a refused connection is a Transport error"):
      for
        port   <- ZIO.succeed(
                    Using.resource(ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress))(_.getLocalPort),
                  )
        client <- ZIO.service[HttpClient]
        result <- client.getString(HttpRequest(Url(s"http://127.0.0.1:$port/")), limit).either
      yield assertTrue(result.left.exists(_.isInstanceOf[HttpError.Transport]))
    ,
    test("a malformed URL is a Transport error rather than an exception"):
      for
        client <- ZIO.service[HttpClient]
        result <- client.getString(HttpRequest(Url("not a url")), limit).either
      yield assertTrue(result.left.exists(_.isInstanceOf[HttpError.Transport])),
  ).provideLayerShared(ZioHttpClient.live)

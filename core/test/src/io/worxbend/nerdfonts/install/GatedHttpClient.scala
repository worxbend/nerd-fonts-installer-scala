package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.HttpResponse
import io.worxbend.nerdfonts.http.Overflow
import io.worxbend.nerdfonts.http.Url

import java.util.concurrent.CountDownLatch

import zio.Scope
import zio.ZIO

/**
 * Wraps a delegate `HttpClient`, holding any request whose URL has a gate until that gate's latch counts
 * down, so a test can force one family's download to start after another's without a real race. The wait
 * is a `CountDownLatch.await()`, which responds to fiber interruption exactly like the delegate's own
 * blocking reads, so an interrupted family still unblocks its gate rather than leaking a fiber.
 */
final private[install] class GatedHttpClient(delegate: HttpClient, gates: Map[Url, CountDownLatch])
    extends HttpClient:
  def get(
      request: HttpRequest,
      limit: ByteLimit,
      overflow: Overflow = Overflow.Reject,
  ): ZIO[Scope, HttpError, HttpResponse] = gates.get(request.url) match
    case None        => delegate.get(request, limit, overflow)
    case Some(latch) =>
      ZIO.attemptBlockingInterrupt(latch.await()).orDie *> delegate.get(request, limit, overflow)

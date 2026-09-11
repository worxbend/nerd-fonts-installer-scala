package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.HttpClient
import io.worxbend.nerdfonts.http.HttpError
import io.worxbend.nerdfonts.http.HttpRequest
import io.worxbend.nerdfonts.http.Overflow
import io.worxbend.nerdfonts.http.Url

import java.io.InputStream
import java.util.concurrent.CountDownLatch

/** Holds selected requests until a latch opens, so a test can order concurrent families deterministically. */
final private[install] class GatedHttpClient(delegate: HttpClient, gates: Map[Url, CountDownLatch])
    extends HttpClient:
  def get[A](request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject)(
      consume: InputStream => A,
  ): Either[HttpError, A] =
    gates.get(request.url).foreach(_.await())
    delegate.get(request, limit, overflow)(consume)

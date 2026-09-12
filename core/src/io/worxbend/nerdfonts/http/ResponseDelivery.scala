package io.worxbend.nerdfonts.http

import io.worxbend.nerdfonts.Diagnostics

import zio.IO
import zio.ZIO
import zio.stream.ZStream

/**
 * The response discipline every `HttpClient` shares, kept in one place so the production adapter and the
 * test fake cannot disagree about it: a non-2xx status and a too-large `Content-Length` are refused before
 * a caller ever sees a body, and the body itself is capped by [[cappedBody]].
 */
object ResponseDelivery:
  def deliver(
      status: Int,
      contentLength: Option[Long],
      limit: ByteLimit,
      overflow: Overflow,
      body: ZStream[Any, Throwable, Byte],
  ): IO[HttpError, HttpResponse] = refusal(status, contentLength, limit) match
    case Some(error) => ZIO.fail(error)
    case None        => ZIO.succeed(HttpResponse(cappedBody(body, limit, overflow)))

  private def refusal(status: Int, contentLength: Option[Long], limit: ByteLimit): Option[HttpError] =
    if !isSuccess(status) then Some(HttpError.Status(status))
    else contentLength.filter(limit.exceededBy).map(declared => HttpError.TooLarge(limit, Some(declared)))

  private def isSuccess(status: Int): Boolean = status >= 200 && status <= 299

  /**
   * Caps a raw byte stream at `limit`, translating any transport failure into `HttpError.Transport` along
   * the way.
   *
   * Enforced chunk-at-a-time, never byte-at-a-time: a per-byte `mapAccumZIO` would dominate the cost of
   * hashing and copying a 768 MiB archive. `Overflow.Truncate` relies on `ZStream#take`, which already stops
   * pulling upstream once enough elements have been produced; `Overflow.Reject` threads a running total
   * through `chunks.mapAccumZIO` and fails with `TooLarge` the moment a chunk would push the total past
   * `limit`, without ever needing to buffer the excess.
   */
  private def cappedBody(
      body: ZStream[Any, Throwable, Byte],
      limit: ByteLimit,
      overflow: Overflow,
  ): ZStream[Any, HttpError, Byte] =
    val transported = body.mapError(error => HttpError.Transport(Diagnostics.describe(error)))
    overflow match
      case Overflow.Truncate => transported.take(limit.value)
      case Overflow.Reject   => transported.chunks
          .mapAccumZIO(0L): (total, chunk) =>
            val newTotal = total + chunk.length
            if limit.exceededBy(newTotal) then ZIO.fail(HttpError.TooLarge(limit, None))
            else ZIO.succeed((newTotal, chunk))
          .flattenChunks

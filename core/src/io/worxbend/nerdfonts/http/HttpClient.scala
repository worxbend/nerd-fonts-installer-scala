package io.worxbend.nerdfonts.http

import java.nio.charset.StandardCharsets

import zio.Chunk
import zio.IO
import zio.Scope
import zio.ZIO
import zio.stream.ZStream

/**
 * The one HTTP port: a streaming GET, so a body is never materialised by the port itself. That shape is
 * what lets a 768 MiB font archive be hashed and copied to disk without ever being held in memory, and it
 * makes the byte caps the port's responsibility rather than every caller's.
 *
 * Contract for implementations (see [[ResponseDelivery]], which enforces it): non-2xx → `Status` before a
 * response is returned; `Content-Length > limit` → `TooLarge` before a response is returned; the returned
 * `HttpResponse.body` is capped at `limit`; with `Overflow.Reject` a body longer than `limit` fails the
 * stream with `TooLarge` at the point the excess is detected; with `Overflow.Truncate` the stream ends at
 * `limit` and never fails on size alone.
 *
 * The `Scope` in the result controls the lifetime of the underlying connection: it must stay open for as
 * long as `body` is being pulled, and callers open it with `ZIO.scoped` around the whole read.
 */
trait HttpClient:
  def get(
      request: HttpRequest,
      limit: ByteLimit,
      overflow: Overflow = Overflow.Reject,
  ): ZIO[Scope, HttpError, HttpResponse]

/**
 * A delivered response: only the body, because a non-2xx status or an oversized `Content-Length` is
 * already a failure by the time a caller sees this value.
 */
final case class HttpResponse(body: ZStream[Any, HttpError, Byte])

object HttpClient:
  extension (client: HttpClient)
    /** A GET whose whole body is collected into memory, within the cap. */
    def getBytes(
        request: HttpRequest,
        limit: ByteLimit,
        overflow: Overflow = Overflow.Reject,
    ): IO[HttpError, Chunk[Byte]] =
      ZIO.scoped(client.get(request, limit, overflow).flatMap(_.body.runCollect))

    /** A GET whose whole body is small text (an API page, a checksum manifest), decoded as UTF-8. */
    def getString(
        request: HttpRequest,
        limit: ByteLimit,
        overflow: Overflow = Overflow.Reject,
    ): IO[HttpError, String] =
      getBytes(request, limit, overflow).map(bytes => String(bytes.toArray, StandardCharsets.UTF_8))

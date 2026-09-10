package io.worxbend.nerdfonts.http

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * The one HTTP port: a loan-shaped GET, so a body is streamed to `consume` and never materialised by the
 * port. That shape is what lets a 768 MiB font archive be hashed and copied to disk without ever being held
 * in memory, and it makes the byte caps the port's responsibility rather than every caller's.
 *
 * Contract for implementations (see [[ResponseDelivery]], which enforces it): non-2xx → `Status` before
 * `consume`; `Content-Length > limit` → `TooLarge` before `consume`; `consume` receives a
 * [[BoundedInputStream]] and is called at most once; the body is closed on every path, including when
 * `consume` throws; with `Overflow.Reject` a body longer than `limit` turns the result into `TooLarge` even
 * though `consume` returned normally; with `Overflow.Truncate` the stream ends at `limit`.
 *
 * `consume` must not retain the stream, and its own exceptions propagate unchanged: a copy failure is the
 * caller's error to classify, not a transport error.
 */
trait HttpClient:
  def get[A](request: HttpRequest, limit: ByteLimit, overflow: Overflow = Overflow.Reject)(
      consume: InputStream => A,
  ): Either[HttpError, A]

object HttpClient:
  extension (client: HttpClient)
    /** A GET whose whole body is small text (an API page, a checksum manifest), decoded as UTF-8. */
    def getString(
        request: HttpRequest,
        limit: ByteLimit,
        overflow: Overflow = Overflow.Reject,
    ): Either[HttpError, String] =
      client.get(request, limit, overflow)(body => String(body.readAllBytes(), StandardCharsets.UTF_8))

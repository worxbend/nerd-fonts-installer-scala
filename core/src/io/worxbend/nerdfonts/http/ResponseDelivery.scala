package io.worxbend.nerdfonts.http

import java.io.InputStream

import scala.util.Using

/**
 * A response as an adapter sees it, before any policy has been applied. `body` is owned by the delivery
 * below from the moment it is passed in; adapters must not touch it afterwards.
 */
final case class RawResponse(status: Int, contentLength: Option[Long], body: InputStream)

/**
 * The response discipline every `HttpClient` shares, kept in one place so the JDK adapter and the test
 * fake cannot disagree about it: a non-2xx status and a too-large `Content-Length` are refused before
 * `consume` sees a byte, the body is capped by [[BoundedInputStream]], it is closed on every path, and an
 * overflow in `Overflow.Reject` mode overrides whatever `consume` returned.
 */
object ResponseDelivery:
  def deliver[A](response: RawResponse, limit: ByteLimit, overflow: Overflow)(
      consume: InputStream => A,
  ): Either[HttpError, A] = Using.resource(BoundedInputStream(response.body, limit, overflow)): body =>
    refusal(response, limit) match
      case Some(error) => Left(error)
      case None        => consumed(body, limit, overflow, consume)

  private def refusal(response: RawResponse, limit: ByteLimit): Option[HttpError] =
    if !isSuccess(response.status) then Some(HttpError.Status(response.status))
    else
      response.contentLength
        .filter(limit.exceededBy)
        .map(declared => HttpError.TooLarge(limit, Some(declared)))

  private def consumed[A](
      body: BoundedInputStream,
      limit: ByteLimit,
      overflow: Overflow,
      consume: InputStream => A,
  ): Either[HttpError, A] =
    val result = consume(body)
    overflow match
      case Overflow.Reject if body.exceeded    => Left(HttpError.TooLarge(limit, None))
      case Overflow.Reject | Overflow.Truncate => Right(result)

  private def isSuccess(status: Int): Boolean = status >= 200 && status <= 299

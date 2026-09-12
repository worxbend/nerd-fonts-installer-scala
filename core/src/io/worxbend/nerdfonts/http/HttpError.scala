package io.worxbend.nerdfonts.http

/**
 * Why a GET did not deliver a body to `consume`.
 *
 * `TooLarge` carries the declared `Content-Length` when the adapter rejected the response on the header
 * alone, so that the message can print that number when it is known and only the bare limit otherwise.
 */
enum HttpError:
  case Transport(cause: String)
  case Status(code: Int)
  case TooLarge(limit: ByteLimit, contentLength: Option[Long])

  /** The failure text without any operation prefix; callers add `download <url>: ` and friends. */
  def render: String = this match
    case Transport(cause)                => cause
    case status: Status                  => status.statusLine
    case TooLarge(limit, Some(declared)) => s"size $declared bytes exceeds ${limit.render} byte limit"
    case TooLarge(limit, None)           => s"exceeds ${limit.render} byte limit"

object HttpError:
  extension (status: Status)
    /**
     * `"<code> <reason>"`, or just `"<code>"` for an unregistered code. `java.net.http` exposes no reason
     * phrase, so it is looked up here, and every user-facing rendering of a non-2xx response must go
     * through here to keep the wording identical.
     */
    def statusLine: String =
      HttpStatus.reasonPhrase(status.code).fold(status.code.toString)(reason => s"${status.code} $reason")

package io.worxbend.nerdfonts.http

/**
 * Why a GET did not deliver a body to `consume`.
 *
 * `TooLarge` carries the declared `Content-Length` when the adapter rejected the response on the header
 * alone, because the Go reference prints that number when it has it and only the bare limit otherwise.
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
     * phrase while Go's `resp.Status` always carries one, so every user-facing rendering of a non-2xx
     * response must go through here to keep the wording identical.
     */
    def statusLine: String =
      HttpStatus.reasonPhrase(status.code).fold(status.code.toString)(reason => s"${status.code} $reason")

/** The IANA status code registry, worded as Go's `http.StatusText` prints it. */
object HttpStatus:
  def reasonPhrase(code: Int): Option[String] = phrases.get(code)

  private val phrases: Map[Int, String] = Map(
    100 -> "Continue",
    101 -> "Switching Protocols",
    102 -> "Processing",
    103 -> "Early Hints",
    200 -> "OK",
    201 -> "Created",
    202 -> "Accepted",
    203 -> "Non-Authoritative Information",
    204 -> "No Content",
    205 -> "Reset Content",
    206 -> "Partial Content",
    207 -> "Multi-Status",
    208 -> "Already Reported",
    226 -> "IM Used",
    300 -> "Multiple Choices",
    301 -> "Moved Permanently",
    302 -> "Found",
    303 -> "See Other",
    304 -> "Not Modified",
    305 -> "Use Proxy",
    307 -> "Temporary Redirect",
    308 -> "Permanent Redirect",
    400 -> "Bad Request",
    401 -> "Unauthorized",
    402 -> "Payment Required",
    403 -> "Forbidden",
    404 -> "Not Found",
    405 -> "Method Not Allowed",
    406 -> "Not Acceptable",
    407 -> "Proxy Authentication Required",
    408 -> "Request Timeout",
    409 -> "Conflict",
    410 -> "Gone",
    411 -> "Length Required",
    412 -> "Precondition Failed",
    413 -> "Request Entity Too Large",
    414 -> "Request URI Too Long",
    415 -> "Unsupported Media Type",
    416 -> "Requested Range Not Satisfiable",
    417 -> "Expectation Failed",
    418 -> "I'm a teapot",
    421 -> "Misdirected Request",
    422 -> "Unprocessable Entity",
    423 -> "Locked",
    424 -> "Failed Dependency",
    425 -> "Too Early",
    426 -> "Upgrade Required",
    428 -> "Precondition Required",
    429 -> "Too Many Requests",
    431 -> "Request Header Fields Too Large",
    451 -> "Unavailable For Legal Reasons",
    500 -> "Internal Server Error",
    501 -> "Not Implemented",
    502 -> "Bad Gateway",
    503 -> "Service Unavailable",
    504 -> "Gateway Timeout",
    505 -> "HTTP Version Not Supported",
    506 -> "Variant Also Negotiates",
    507 -> "Insufficient Storage",
    508 -> "Loop Detected",
    510 -> "Not Extended",
    511 -> "Network Authentication Required",
  )

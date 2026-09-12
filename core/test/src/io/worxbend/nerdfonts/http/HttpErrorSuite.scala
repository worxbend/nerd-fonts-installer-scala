package io.worxbend.nerdfonts.http

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object HttpErrorSuite extends ZIOSpecDefault:
  def spec = suite("HttpError")(
    test("statusLine renders registered codes with their reason phrase"):
      assertTrue(
        HttpError.Status(404).statusLine == "404 Not Found",
        HttpError.Status(403).statusLine == "403 Forbidden",
        HttpError.Status(429).statusLine == "429 Too Many Requests",
      )
    ,
    test("statusLine falls back to the bare number for an unregistered code"):
      assertTrue(
        HttpError.Status(599).statusLine == "599",
        HttpStatus.reasonPhrase(599).isEmpty,
      )
    ,
    test("the reason table covers every class from 1xx to 5xx"):
      assertTrue(
        HttpStatus.reasonPhrase(100) == Some("Continue"),
        HttpStatus.reasonPhrase(200) == Some("OK"),
        HttpStatus.reasonPhrase(308) == Some("Permanent Redirect"),
        HttpStatus.reasonPhrase(451) == Some("Unavailable For Legal Reasons"),
        HttpStatus.reasonPhrase(511) == Some("Network Authentication Required"),
      )
    ,
    test("a status error renders as its status line"):
      assertTrue(HttpError.Status(404).render == "404 Not Found")
    ,
    test("a transport error renders its cause"):
      assertTrue(HttpError.Transport("connection refused").render == "connection refused")
    ,
    test("a too-large error renders the declared size when known"):
      assertTrue(
        HttpError.TooLarge(ByteLimit.bytes(10), Some(12L)).render == "size 12 bytes exceeds 10 byte limit",
      )
    ,
    test("a too-large error renders only the limit when the size was discovered while streaming"):
      assertTrue(HttpError.TooLarge(ByteLimit.mebibytes(1), None).render == "exceeds 1048576 byte limit")
    ,
    test("byte limits convert units exactly"):
      assertTrue(
        ByteLimit.mebibytes(768).value == 805306368L,
        ByteLimit.gibibytes(2).value == 2147483648L,
      )
    ,
    test("a byte limit knows when a count breaks it"):
      assertTrue(ByteLimit.bytes(4).exceededBy(5), !ByteLimit.bytes(4).exceededBy(4))
    ,
    test("a negative byte limit is a programming error"):
      assertTrue(
        scala.util.Try(ByteLimit.bytes(-1)).failed.toOption.exists(_.isInstanceOf[IllegalArgumentException]),
      ),
  )

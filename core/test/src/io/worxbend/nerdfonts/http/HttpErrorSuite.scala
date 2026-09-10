package io.worxbend.nerdfonts.http

final class HttpErrorSuite extends munit.FunSuite:
  test("statusLine renders registered codes with their reason phrase"):
    assertEquals(HttpError.Status(404).statusLine, "404 Not Found")
    assertEquals(HttpError.Status(403).statusLine, "403 Forbidden")
    assertEquals(HttpError.Status(429).statusLine, "429 Too Many Requests")

  test("statusLine falls back to the bare number for an unregistered code"):
    assertEquals(HttpError.Status(599).statusLine, "599")
    assertEquals(HttpStatus.reasonPhrase(599), None)

  test("the reason table covers every class from 1xx to 5xx"):
    assertEquals(HttpStatus.reasonPhrase(100), Some("Continue"))
    assertEquals(HttpStatus.reasonPhrase(200), Some("OK"))
    assertEquals(HttpStatus.reasonPhrase(308), Some("Permanent Redirect"))
    assertEquals(HttpStatus.reasonPhrase(451), Some("Unavailable For Legal Reasons"))
    assertEquals(HttpStatus.reasonPhrase(511), Some("Network Authentication Required"))

  test("a status error renders as its status line"):
    assertEquals(HttpError.Status(404).render, "404 Not Found")

  test("a transport error renders its cause"):
    assertEquals(HttpError.Transport("connection refused").render, "connection refused")

  test("a too-large error renders the declared size when known"):
    assertEquals(
      HttpError.TooLarge(ByteLimit.bytes(10), Some(12L)).render,
      "size 12 bytes exceeds 10 byte limit",
    )

  test("a too-large error renders only the limit when the size was discovered while streaming"):
    assertEquals(HttpError.TooLarge(ByteLimit.mebibytes(1), None).render, "exceeds 1048576 byte limit")

  test("byte limits convert units exactly"):
    assertEquals(ByteLimit.mebibytes(768).value, 805306368L)
    assertEquals(ByteLimit.gibibytes(2).value, 2147483648L)

  test("a byte limit knows when a count breaks it"):
    assert(ByteLimit.bytes(4).exceededBy(5))
    assert(!ByteLimit.bytes(4).exceededBy(4))

  test("a negative byte limit is a programming error"):
    intercept[IllegalArgumentException](ByteLimit.bytes(-1))

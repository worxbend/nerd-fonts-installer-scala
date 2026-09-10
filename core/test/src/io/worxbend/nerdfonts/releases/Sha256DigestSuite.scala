package io.worxbend.nerdfonts.releases

final class Sha256DigestSuite extends munit.FunSuite:
  private val hex = "0123456789abcdef" * 4

  test("accepts 64 hex characters"):
    assertEquals(Sha256Digest.parse(hex).map(_.value), Some(hex))

  test("lowercases and trims"):
    assertEquals(Sha256Digest.parse(s" ${hex.toUpperCase} ").map(_.value), Some(hex))

  test("rejects the wrong length"):
    assertEquals(Sha256Digest.parse(hex.take(63)), None)
    assertEquals(Sha256Digest.parse(hex + "0"), None)

  test("rejects non-hex characters"):
    assertEquals(Sha256Digest.parse("g" * 64), None)

  test("renders 32 bytes as lowercase hex"):
    val bytes = Array.tabulate[Byte](32)(i => (i * 8).toByte)
    assertEquals(Sha256Digest.fromBytes(bytes).value, bytes.map(b => f"${b & 0xff}%02x").mkString)

  test("a digest of the wrong byte length is a programming error"):
    intercept[IllegalArgumentException](Sha256Digest.fromBytes(Array.ofDim[Byte](20)))

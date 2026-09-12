package io.worxbend.nerdfonts.releases

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object Sha256DigestSuite extends ZIOSpecDefault:
  private val hex = "0123456789abcdef" * 4

  def spec = suite("Sha256Digest")(
    test("accepts 64 hex characters"):
      assertTrue(Sha256Digest.parse(hex).map(_.value) == Some(hex))
    ,
    test("lowercases and trims"):
      assertTrue(Sha256Digest.parse(s" ${hex.toUpperCase} ").map(_.value) == Some(hex))
    ,
    test("rejects the wrong length"):
      assertTrue(
        Sha256Digest.parse(hex.take(63)).isEmpty,
        Sha256Digest.parse(hex + "0").isEmpty,
      )
    ,
    test("rejects non-hex characters"):
      assertTrue(Sha256Digest.parse("g" * 64).isEmpty)
    ,
    test("renders 32 bytes as lowercase hex"):
      val bytes = Array.tabulate[Byte](32)(i => (i * 8).toByte)
      assertTrue(Sha256Digest.fromBytes(bytes).value == bytes.map(b => f"${b & 0xff}%02x").mkString)
    ,
    test("a digest of the wrong byte length is a programming error"):
      assertTrue(
        scala.util
          .Try(Sha256Digest.fromBytes(Array.ofDim[Byte](20)))
          .failed
          .toOption
          .exists(
            _.isInstanceOf[IllegalArgumentException],
          ),
      ),
  )

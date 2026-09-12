package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.FamilyName
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

import java.security.MessageDigest

object ChecksumManifestSuite extends ZIOSpecDefault:
  private val hackDigest      = "a" * 64
  private val jetBrainsDigest = "b" * 64

  private def family(name: String): FamilyName = FamilyName.parse(name) match
    case Right(value) => value
    case Left(error)  => throw new AssertionError(s"unsafe $name: ${error.render}")

  private def digest(hex: String): Sha256Digest = Sha256Digest.parse(hex) match
    case Some(value) => value
    case None        => throw new AssertionError(s"bad digest $hex")

  private def parsed(text: String): Map[String, String] =
    ChecksumManifest.parse(text).map((name, sha) => name.value -> sha.value)

  def spec = suite("ChecksumManifest")(
    test("parses the two-column format and ignores a single-column line"):
      val manifest = s"$hackDigest  Hack.zip\n$jetBrainsDigest  JetBrainsMono.ZIP\nskipped\n"
      assertTrue(parsed(manifest) == Map("Hack" -> hackDigest, "JetBrainsMono" -> jetBrainsDigest))
    ,
    test("normalises digests to lowercase"):
      assertTrue(
        parsed(s"${"ABCDEF0123".padTo(64, 'A')}  Hack.zip") == Map("Hack" -> "abcdef0123".padTo(64, 'a')),
      )
    ,
    test("ignores lines for non-zip assets"):
      val manifest = s"${"1" * 64}  Hack.tar.gz\n$hackDigest  Hack.zip\n${"3" * 64}  README.md\n"
      assertTrue(parsed(manifest) == Map("Hack" -> hackDigest))
    ,
    test("skips a stem that is not a valid family name"):
      assertTrue(parsed(s"$hackDigest  ../Hack.zip\n$hackDigest  .zip") == Map.empty)
    ,
    test("skips a digest that is not 64 hex characters"):
      assertTrue(parsed("abc123  Hack.zip") == Map.empty)
    ,
    test("skips a last line cut by the read limit"):
      val manifest = s"$hackDigest  Hack.zip\n${jetBrainsDigest.take(40)}"
      assertTrue(parsed(manifest) == Map("Hack" -> hackDigest))
    ,
    test("tolerates CRLF line endings"):
      assertTrue(parsed(s"$hackDigest  Hack.zip\r\n") == Map("Hack" -> hackDigest))
    ,
    test("a later entry for the same family wins"):
      assertTrue(
        parsed(s"$hackDigest  Hack.zip\n$jetBrainsDigest  Hack.zip") == Map("Hack" -> jetBrainsDigest),
      )
    ,
    test("a parsed digest equals the digest computed from bytes"):
      val bytes    = "payload".getBytes
      val computed = Sha256Digest.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))
      assertTrue(
        digest(computed.value) == computed,
        ChecksumManifest.parse(s"${computed.value}  Hack.zip") == Map(family("Hack") -> computed),
      ),
  )

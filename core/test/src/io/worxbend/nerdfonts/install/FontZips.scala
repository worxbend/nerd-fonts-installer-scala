package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.releases.Sha256Digest

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.util.Using

/**
 * Builds zip fixtures in memory. `deflated` entries carry their sizes in a trailing data descriptor, so a
 * streaming reader sees no declared size; `stored` entries declare it in the local header, which is what a
 * real `zip`-built Nerd Fonts archive does and what the declared-size checks need.
 */
private[install] object FontZips:
  def deflated(entries: (String, String)*): Array[Byte] = build(entries, declareSizes = false)

  def stored(entries: (String, String)*): Array[Byte] = build(entries, declareSizes = true)

  /** The archive every family downloads in the engine tests: one `<Family>.ttf` with four bytes of content. */
  def family(name: String): Array[Byte] = deflated(s"$name.ttf" -> "font")

  val noFonts: Array[Byte] = deflated("README.md" -> "docs")

  def digest(bytes: Array[Byte]): Sha256Digest =
    Sha256Digest.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes))

  def write(dir: os.Path, name: String, bytes: Array[Byte]): os.Path =
    val path = dir / name
    os.write(path, bytes)
    path

  private def build(entries: Seq[(String, String)], declareSizes: Boolean): Array[Byte] =
    val buffer = ByteArrayOutputStream()
    Using.resource(ZipOutputStream(buffer)): zip =>
      entries.foreach: (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        zip.putNextEntry(if declareSizes then storedEntry(name, bytes) else ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    buffer.toByteArray

  private def storedEntry(name: String, bytes: Array[Byte]): ZipEntry =
    val crc   = CRC32()
    crc.update(bytes)
    val entry = ZipEntry(name)
    entry.setMethod(ZipEntry.STORED)
    entry.setSize(bytes.length.toLong)
    entry.setCompressedSize(bytes.length.toLong)
    entry.setCrc(crc.getValue)
    entry

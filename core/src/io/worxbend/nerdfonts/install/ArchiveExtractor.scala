package io.worxbend.nerdfonts.install

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.http.BoundedInputStream
import io.worxbend.nerdfonts.http.ByteLimit
import io.worxbend.nerdfonts.http.Overflow

import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

import scala.annotation.tailrec
import scala.util.Try
import scala.util.Using

import ox.discard
import ox.either
import ox.either.catching
import ox.either.ok

/**
 * Extracts the font files of a downloaded archive into a staging directory.
 *
 * Only `.ttf`, `.otf` and `.ttc` entries are written, flattened to their base name: the archive is untrusted
 * upstream data, so an entry's path never decides where a byte lands, and directories, licences and readmes
 * never reach the font directory. Two caps bound a decompression bomb: an entry's declared size is refused
 * before a byte is inflated, the stream is then capped at `fontFile + 1` so a lying header is caught as well,
 * and a running total enforces `archive`. Every written file is fsynced and closed explicitly, because a
 * silently dropped flush error (ENOSPC, EIO, quota) would promote a truncated font as a successful install.
 */
object ArchiveExtractor:
  private val fontExtensions = Set("ttf", "otf", "ttc")

  def extract(zip: os.Path, into: os.Path, limits: SizeLimits): Either[ArchiveError, ExtractedCount] =
    open(zip).flatMap: entries =>
      Using.resource(entries)(stream => extractAll(Session(zip, into, limits, stream), Progress.start))

  // `NOFOLLOW_LINKS`: the temp zip was already digest-verified by path before this re-open, so a symlink
  // swapped into that name in the meantime (a shared, non-sticky `$TMPDIR`) must fail loudly rather than be
  // followed into extracting an unverified file.
  private def open(zip: os.Path): Either[ArchiveError, ZipInputStream] = ZipInputStream(
    Files.newInputStream(zip.toNIO, LinkOption.NOFOLLOW_LINKS),
  )
    .catching[IOException]
    .left
    .map(error => ArchiveError.Open(zip, Diagnostics.describe(error)))

  @tailrec
  private def extractAll(session: Session, progress: Progress): Either[ArchiveError, ExtractedCount] =
    nextEntry(session) match
      case Left(error)        => Left(error)
      case Right(None)        => finished(session.zip, progress)
      case Right(Some(entry)) => handle(session, entry, progress) match
          case Left(error) => Left(error)
          case Right(next) => extractAll(session, next)

  // A header that cannot be read is reported as an unopenable archive: Go's `zip.OpenReader` validates the
  // whole directory up front, so this is the closest equivalent for a corrupt file.
  private def nextEntry(session: Session): Either[ArchiveError, Option[ZipEntry]] = Option(
    session.entries.getNextEntry,
  )
    .catching[IOException]
    .left
    .map(error => ArchiveError.Open(session.zip, Diagnostics.describe(error)))

  private def handle(session: Session, entry: ZipEntry, progress: Progress): Either[ArchiveError, Progress] =
    val base = baseName(entry.getName)
    if entry.isDirectory || !isFontFile(base) then Right(progress)
    else
      either:
        refuseDeclared(session, entry, declaredSize(entry), progress).ok()
        val next = progress.add(writeEntry(session, entry, base).ok())
        refuseTotal(session, next.totalBytes).ok()
        next

  private def finished(zip: os.Path, progress: Progress): Either[ArchiveError, ExtractedCount] =
    if progress.extracted == 0 then Left(ArchiveError.NoFontFiles(zip))
    else Right(ExtractedCount(progress.extracted))

  // A streaming reader only knows the size when the local header carries it; `-1` means "in the trailing
  // data descriptor", in which case the stream cap and the running total after the copy are the guards.
  private def declaredSize(entry: ZipEntry): Option[Long] = Option(entry.getSize).filter(_ >= 0)

  private def refuseDeclared(
      session: Session,
      entry: ZipEntry,
      declared: Option[Long],
      progress: Progress,
  ): Either[ArchiveError, Unit] = declared match
    case Some(size) if session.limits.fontFile.exceededBy(size) =>
      Left(ArchiveError.EntryTooLarge(session.zip, entry.getName, size, session.limits.fontFile))
    case Some(size)                                             => refuseTotal(session, progress.totalBytes + size)
    case None                                                   => Right(())

  private def refuseTotal(session: Session, totalBytes: Long): Either[ArchiveError, Unit] =
    if session.limits.archive.exceededBy(totalBytes) then
      Left(ArchiveError.ArchiveTooLarge(session.zip, session.limits.archive))
    else Right(())

  private def writeEntry(session: Session, entry: ZipEntry, base: String): Either[ArchiveError, Long] =
    writeFont(session, entry.getName, base).left.map(ArchiveError.Entry(entry.getName, _))

  // Works entirely in the entry-error domain; the safety-net close in `finally` covers the early exits, the
  // explicit `close` above it is the one whose error matters.
  private def writeFont(session: Session, name: String, base: String): Either[ArchiveEntryError, Long] =
    either:
      val target = targetOf(session.into, base).left.map(ArchiveEntryError.InvalidName(name, _)).ok()
      val out    = openOutput(target).ok()
      try
        val written = copyCapped(session, out, target, name).ok()
        sync(out, target).ok()
        close(out, target).ok()
        written
      finally Try(out.close()).discard

  private def targetOf(into: os.Path, base: String): Either[String, os.Path] =
    (into / base).catching[IllegalArgumentException].left.map(Diagnostics.describe)

  private def openOutput(target: os.Path): Either[ArchiveEntryError, FileOutputStream] = FileOutputStream(
    target.toIO,
  )
    .catching[IOException]
    .left
    .map(error => ArchiveEntryError.Create(target, Diagnostics.describe(error)))

  // The cap is `limit + 1` so an entry that runs past its declared size is detected without buffering.
  private def copyCapped(
      session: Session,
      out: FileOutputStream,
      target: os.Path,
      name: String,
  ): Either[ArchiveEntryError, Long] =
    val limit  = session.limits.fontFile
    val capped = BoundedInputStream(session.entries, limit, Overflow.Reject)
    capped.transferTo(out).catching[IOException] match
      case Left(error)                                 => Left(ArchiveEntryError.Copy(name, target, Diagnostics.describe(error)))
      case Right(written) if limit.exceededBy(written) => Left(ArchiveEntryError.Oversize(name, limit))
      case Right(written)                              => Right(written)

  private def sync(out: FileOutputStream, target: os.Path): Either[ArchiveEntryError, Unit] = out.getFD
    .sync()
    .catching[IOException]
    .left
    .map(error => ArchiveEntryError.Flush(target, Diagnostics.describe(error)))

  private def close(out: FileOutputStream, target: os.Path): Either[ArchiveEntryError, Unit] = out
    .close()
    .catching[IOException]
    .left
    .map(error => ArchiveEntryError.Finalize(target, Diagnostics.describe(error)))

  // Go's `filepath.Base` / `filepath.Ext` on a POSIX host: only `/` separates, a backslash is an ordinary
  // character, and the extension is whatever follows the last dot of the last element.
  private def baseName(name: String): String = name.split('/').lastOption.getOrElse("")

  private def isFontFile(base: String): Boolean = base.lastIndexOf('.') match
    case -1    => false
    case index => fontExtensions.contains(base.substring(index + 1).toLowerCase)

  /** One extraction pass: the archive being read and where its fonts go. */
  final private case class Session(zip: os.Path, into: os.Path, limits: SizeLimits, entries: ZipInputStream)

  /** The immutable running state threaded through the entry loop. */
  final private case class Progress(extracted: Int, totalBytes: Long):
    def add(written: Long): Progress = Progress(extracted + 1, totalBytes + written)

  private object Progress:
    val start: Progress = Progress(0, 0L)

/** How many font files an extraction wrote; distinct from a bare `Int` so it cannot be confused with a size. */
opaque type ExtractedCount = Int

object ExtractedCount:
  private[install] def apply(value: Int): ExtractedCount = value

  extension (count: ExtractedCount) def value: Int = count

/**
 * Why an archive could not be extracted. Each case carries the archive path because the Go messages name it
 * even though the caller's `extract <zip> to <staging>: ` prefix repeats it; that doubling is the reference
 * output and is kept.
 */
enum ArchiveError:
  case Open(zip: os.Path, cause: String)
  case NoFontFiles(zip: os.Path)
  case EntryTooLarge(zip: os.Path, entry: String, declared: Long, limit: ByteLimit)
  case ArchiveTooLarge(zip: os.Path, limit: ByteLimit)
  case Entry(entry: String, cause: ArchiveEntryError)

  def render: String = this match
    case Open(zip, cause)                           => s"open font zip $zip: $cause"
    case NoFontFiles(zip)                           => s"extract $zip: no font files found"
    case EntryTooLarge(zip, entry, declared, limit) =>
      s"extract $zip: font file $entry declares $declared bytes, exceeds ${limit.render} byte limit"
    case ArchiveTooLarge(zip, limit)                =>
      s"extract $zip: total uncompressed size exceeds ${limit.render} byte limit"
    case Entry(entry, cause)                        => s"extract $entry: ${cause.render}"

/** Why one font entry could not be written, in the order the steps happen. */
enum ArchiveEntryError:
  case InvalidName(entry: String, cause: String)
  case Create(target: os.Path, cause: String)
  case Copy(entry: String, target: os.Path, cause: String)
  case Oversize(entry: String, limit: ByteLimit)
  case Flush(target: os.Path, cause: String)
  case Finalize(target: os.Path, cause: String)

  def render: String = this match
    case InvalidName(entry, cause)  => s"invalid font file name $entry: $cause"
    case Create(target, cause)      => s"create font file $target: $cause"
    case Copy(entry, target, cause) => s"copy font file $entry to $target: $cause"
    case Oversize(entry, limit)     => s"font file $entry exceeds ${limit.render} byte limit"
    case Flush(target, cause)       => s"flush font file $target: $cause"
    case Finalize(target, cause)    => s"finalize font file $target: $cause"

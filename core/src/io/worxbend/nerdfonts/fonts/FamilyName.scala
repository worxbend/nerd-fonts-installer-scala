package io.worxbend.nerdfonts.fonts

/**
 * A Nerd Font family name that is safe to use as a single path component and as a URL path segment.
 *
 * This is the one path-traversal guard in the application: a family name is joined onto the destination
 * directory and onto the download URL, so every name must pass [[FamilyName.parse]] before it reaches the
 * filesystem or the network. The config loader validates through this type, so unsafe names cannot cross into
 * installation.
 */
opaque type FamilyName = String

object FamilyName:
  /**
   * Validates a raw name with exactly the rules of the Go reference. The input is not trimmed: callers that
   * accept whitespace-padded input (the config loader) trim before parsing, as the Go loader does.
   */
  def parse(raw: String): Either[FamilyNameError, FamilyName] =
    if raw.isEmpty then Left(FamilyNameError.Empty)
    else if isUnsafe(raw) then Left(FamilyNameError.Unsafe(raw))
    else Right(raw)

  // Absolute paths and names whose base name differs from themselves are implied by the separator check on
  // POSIX, but each Go rule is kept explicit so the guard reads as the security argument it is.
  private def isUnsafe(raw: String): Boolean = raw == "." || raw == ".." ||
    raw.exists(c => c == '/' || c == '\\' || c == '\u0000') ||
    raw.startsWith("/") ||
    baseName(raw) != raw

  private def baseName(raw: String): String = raw.split('/').lastOption.getOrElse("")

  given Ordering[FamilyName] = Ordering.String

  extension (name: FamilyName)
    /** The validated text, for building paths, URLs and messages. */
    def value: String = name

/** Why a raw family name was rejected; renders the Go messages verbatim. */
enum FamilyNameError:
  case Empty
  case Unsafe(name: String)

  def render: String = this match
    case Empty        => "font family names cannot be empty"
    case Unsafe(name) => s"unsafe font family name ${GoQuote.quote(name)}"

package io.worxbend.nerdfonts.fonts

/**
 * The configured install root before tilde expansion, trimmed and never blank.
 *
 * Expansion is deliberately not performed here: it needs the process environment, and keeping the raw
 * spelling lets the CLI echo exactly what the user wrote. `PathExpander` turns this into an absolute
 * `os.Path` right before the install request is built.
 */
opaque type DestinationPath = String

object DestinationPath:
  /** The default install root, shared by config decoding and install request construction. */
  val default: DestinationPath = "~/.local/share/fonts/NerdFonts"

  /** Trims the input and rejects a blank result. */
  def parse(raw: String): Option[DestinationPath] = Option(raw.trim).filter(_.nonEmpty)

  extension (path: DestinationPath)
    /** The unexpanded text as configured. */
    def value: String = path

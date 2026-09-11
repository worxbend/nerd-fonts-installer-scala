package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.fonts.GoQuote

/**
 * Which glyph set the picker draws with. `Auto` exists as a distinct value because it is a valid `--icons`
 * spelling and is echoed back in the side panel; it resolves to the Unicode set, which needs no patched font.
 */
enum IconMode:
  case Auto, Nerd, Unicode, Ascii

  /** The flag spelling, as shown in the release cockpit's `Mode` line. */
  def render: String = this match
    case Auto    => "auto"
    case Nerd    => "nerd"
    case Unicode => "unicode"
    case Ascii   => "ascii"

object IconMode:
  /** Go's `parseIconMode`: trims and lower-cases before matching, but quotes the raw value in the message. */
  def parse(raw: String): Either[IconModeError, IconMode] = raw.trim.toLowerCase match
    case "auto"    => Right(Auto)
    case "nerd"    => Right(Nerd)
    case "unicode" => Right(Unicode)
    case "ascii"   => Right(Ascii)
    case _         => Left(IconModeError.Invalid(raw))

/** Why an `--icons` value was rejected; renders the Go message verbatim, raw value quoted with `%q`. */
enum IconModeError:
  case Invalid(raw: String)

  def render: String = this match
    case Invalid(raw) => s"invalid --icons ${GoQuote.quote(raw)}; use auto, nerd, unicode, or ascii"

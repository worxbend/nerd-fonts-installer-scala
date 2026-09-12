package io.worxbend.nerdfonts.fonts

/**
 * Double-quoting with escapes, for values named in user-facing messages.
 *
 * Every rejected value reaching an error message is untrusted input, so a name containing a NUL byte, an
 * escape sequence or a backslash must appear escaped rather than raw: this is what keeps control characters
 * out of the terminal, where they could otherwise reposition the cursor or inject colour. Non-ASCII printable
 * text is left as is, since it is not a terminal hazard and mangling it would make real names unreadable.
 */
private[nerdfonts] object Quoting:
  def quote(value: String): String = value.flatMap(escape).mkString("\"", "", "\"")

  private def escape(char: Char): String = char match
    case '"'                           => "\\\""
    case '\\'                          => "\\\\"
    case '\n'                          => "\\n"
    case '\r'                          => "\\r"
    case '\t'                          => "\\t"
    case '\u0007'                      => "\\a"
    case '\b'                          => "\\b"
    case '\f'                          => "\\f"
    case '\u000b'                      => "\\v"
    case c if c < ' ' || c == '\u007f' => f"\\x${c.toInt}%02x"
    case c                             => c.toString

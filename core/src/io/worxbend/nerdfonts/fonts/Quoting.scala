package io.worxbend.nerdfonts.fonts

/**
 * Go `%q` quoting for user-facing messages.
 *
 * The Go reference renders offending values with `fmt.Errorf("... %q", value)`, so a name containing a NUL
 * byte or a backslash appears escaped rather than raw. Reproducing that keeps error output identical and,
 * more importantly, keeps control characters out of the terminal. Non-ASCII printable text is left as is;
 * Go escapes only the non-printable subset of it, which never occurs in real font family names.
 */
private[nerdfonts] object GoQuote:
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

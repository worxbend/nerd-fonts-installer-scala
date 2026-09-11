package io.worxbend.nerdfonts

final class TerminalSafeSuite extends munit.FunSuite:
  private val esc = "\u001b"
  private val bel = "\u0007"

  test("ordinary text is unchanged"):
    assertEquals(TerminalSafe.sanitize("Hack Nerd Font"), "Hack Nerd Font")

  test("the escape character is replaced, one character for one character"):
    assertEquals(TerminalSafe.sanitize(s"Hack$esc[31mRed"), "Hack?[31mRed")

  test("every C0 control character and DEL is replaced"):
    val controls = (0 to 31).map(_.toChar) :+ 127.toChar
    controls.foreach(c => assertEquals(TerminalSafe.sanitize(s"a${c}b"), "a?b"))

  test("the C1 range is replaced"):
    val c1 = (128 to 159).map(_.toChar)
    c1.foreach(c => assertEquals(TerminalSafe.sanitize(s"a${c}b"), "a?b"))

  test("printable Latin-1 and beyond survives untouched"):
    assertEquals(TerminalSafe.sanitize("Caf\u00e9 N\u00e9rd"), "Caf\u00e9 N\u00e9rd")

  test("length is preserved so fuzzy-match positions still line up"):
    val hostile = s"Ha$esc]0;pwned${bel}ck"
    assertEquals(TerminalSafe.sanitize(hostile).length, hostile.length)

  test("an OSC title-setting sequence is neutralised"):
    val hostile = s"Hack$esc]0;pwned$esc[2J"
    assertEquals(TerminalSafe.sanitize(hostile), "Hack?]0;pwned?[2J")

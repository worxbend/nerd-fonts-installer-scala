package io.worxbend.nerdfonts

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object TerminalSafeSuite extends ZIOSpecDefault:
  private val esc = "\u001b"
  private val bel = "\u0007"

  def spec = suite("TerminalSafe")(
    test("ordinary text is unchanged"):
      assertTrue(TerminalSafe.sanitize("Hack Nerd Font") == "Hack Nerd Font")
    ,
    test("the escape character is replaced, one character for one character"):
      assertTrue(TerminalSafe.sanitize(s"Hack$esc[31mRed") == "Hack?[31mRed")
    ,
    test("every C0 control character and DEL is replaced"):
      val controls = (0 to 31).map(_.toChar) :+ 127.toChar
      assertTrue(controls.forall(c => TerminalSafe.sanitize(s"a${c}b") == "a?b"))
    ,
    test("the C1 range is replaced"):
      val c1 = (128 to 159).map(_.toChar)
      assertTrue(c1.forall(c => TerminalSafe.sanitize(s"a${c}b") == "a?b"))
    ,
    test("printable Latin-1 and beyond survives untouched"):
      assertTrue(TerminalSafe.sanitize("Caf\u00e9 N\u00e9rd") == "Caf\u00e9 N\u00e9rd")
    ,
    test("length is preserved so fuzzy-match positions still line up"):
      val hostile = s"Ha$esc]0;pwned${bel}ck"
      assertTrue(TerminalSafe.sanitize(hostile).length == hostile.length)
    ,
    test("an OSC title-setting sequence is neutralised"):
      val hostile = s"Hack$esc]0;pwned$esc[2J"
      assertTrue(TerminalSafe.sanitize(hostile) == "Hack?]0;pwned?[2J"),
  )

package io.worxbend.nerdfonts.fonts

import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object FamilyNameSuite extends ZIOSpecDefault:
  private val safeNames   = Vector("Hack", "JetBrainsMono", "Symbols Nerd Font", "0xProto", "Fira-Code_1")
  private val unsafeNames =
    Vector(".", "..", "Hack/Regular", "Hack\\Regular", "/tmp/Hack", "../Hack", "Hack\u0000")

  def spec = suite("FamilyName")(
    test("accepts every name in the acceptance table"):
      assertTrue(safeNames.forall(name => FamilyName.parse(name).map(_.value) == Right(name)))
    ,
    test("rejects every name in the rejection table as unsafe"):
      assertTrue(unsafeNames.forall(name => FamilyName.parse(name) == Left(FamilyNameError.Unsafe(name))))
    ,
    test("rejects the empty name with its own error"):
      assertTrue(FamilyName.parse("") == Left(FamilyNameError.Empty))
    ,
    test("does not trim, so a padded name keeps its padding"):
      assertTrue(FamilyName.parse(" Hack ").map(_.value) == Right(" Hack "))
    ,
    test("renders the empty-name message verbatim"):
      assertTrue(FamilyNameError.Empty.render == "font family names cannot be empty")
    ,
    test("renders the unsafe-name message with the name quoted"):
      assertTrue(FamilyNameError.Unsafe("../x").render == "unsafe font family name \"../x\"")
    ,
    test("renders a NUL byte and a backslash escaped"):
      assertTrue(
        FamilyNameError.Unsafe("Hack\u0000").render == "unsafe font family name \"Hack\\x00\"",
        FamilyNameError.Unsafe("Hack\\Regular").render == "unsafe font family name \"Hack\\\\Regular\"",
      )
    ,
    test("orders names by their text"):
      val names = Vector("Hack", "0xProto", "JetBrainsMono").flatMap(FamilyName.parse(_).toOption)
      assertTrue(names.sorted.map(_.value) == Vector("0xProto", "Hack", "JetBrainsMono")),
  )

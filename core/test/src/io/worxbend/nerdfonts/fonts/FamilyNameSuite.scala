package io.worxbend.nerdfonts.fonts

final class FamilyNameSuite extends munit.FunSuite:
  private val safeNames   = Vector("Hack", "JetBrainsMono", "Symbols Nerd Font", "0xProto", "Fira-Code_1")
  private val unsafeNames =
    Vector(".", "..", "Hack/Regular", "Hack\\Regular", "/tmp/Hack", "../Hack", "Hack\u0000")

  test("accepts every name in the Go acceptance table"):
    safeNames.foreach(name => assertEquals(FamilyName.parse(name).map(_.value), Right(name)))

  test("rejects every name in the Go rejection table as unsafe"):
    unsafeNames.foreach(name => assertEquals(FamilyName.parse(name), Left(FamilyNameError.Unsafe(name))))

  test("rejects the empty name with its own error"):
    assertEquals(FamilyName.parse(""), Left(FamilyNameError.Empty))

  test("does not trim, so a padded name keeps its padding"):
    assertEquals(FamilyName.parse(" Hack ").map(_.value), Right(" Hack "))

  test("renders the empty-name message verbatim"):
    assertEquals(FamilyNameError.Empty.render, "font family names cannot be empty")

  test("renders the unsafe-name message with Go quoting"):
    assertEquals(FamilyNameError.Unsafe("../x").render, "unsafe font family name \"../x\"")

  test("renders a NUL byte and a backslash escaped like Go's %q"):
    assertEquals(FamilyNameError.Unsafe("Hack\u0000").render, "unsafe font family name \"Hack\\x00\"")
    assertEquals(
      FamilyNameError.Unsafe("Hack\\Regular").render,
      "unsafe font family name \"Hack\\\\Regular\"",
    )

  test("orders names by their text"):
    val names = Vector("Hack", "0xProto", "JetBrainsMono").flatMap(FamilyName.parse(_).toOption)
    assertEquals(names.sorted.map(_.value), Vector("0xProto", "Hack", "JetBrainsMono"))

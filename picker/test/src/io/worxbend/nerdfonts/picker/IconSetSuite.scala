package io.worxbend.nerdfonts.picker

/** The Go `icons_test.go` scenarios plus the family-glyph lookup rules. */
final class IconSetSuite extends munit.FunSuite:

  test("each mode resolves to the set carrying that mode"):
    assertEquals(IconSet.forMode(IconMode.Nerd).mode, IconMode.Nerd)
    assertEquals(IconSet.forMode(IconMode.Ascii).mode, IconMode.Ascii)
    assertEquals(IconSet.forMode(IconMode.Unicode).mode, IconMode.Unicode)

  test("checkboxes per mode match the Go tables"):
    assertEquals((IconSet.nerd.checked, IconSet.nerd.unchecked), ("󰄲", "󰄱"))
    assertEquals((IconSet.ascii.checked, IconSet.ascii.unchecked), ("[x]", "[ ]"))
    assertEquals((IconSet.unicode.checked, IconSet.unicode.unchecked), ("☑", "☐"))

  test("the nerd set carries the family glyph table"):
    assertEquals(IconSet.nerd.nerdFamily.get("hack"), Some("󰌌"))
    assertEquals(IconSet.nerd.nerdFamily.get("jetbrainsmono"), Some("\ue70c"))
    assertEquals(IconSet.nerd.nerdFamily.size, 21)

  test("the ascii and unicode sets have no family glyphs"):
    assertEquals(IconSet.ascii.nerdFamily, Map.empty[String, String])
    assertEquals(IconSet.unicode.nerdFamily, Map.empty[String, String])

  test("auto resolves to the unicode set"):
    assertEquals(IconSet.forMode(IconMode.Auto), IconSet.unicode)

  test("family lookup lower-cases and strips spaces, falling back to the font glyph"):
    assertEquals(IconSet.nerd.iconForFamily("Fira Code"), "\ue7a7")
    assertEquals(IconSet.nerd.iconForFamily("Hack"), "󰌌")
    assertEquals(IconSet.nerd.iconForFamily("Unknown"), IconSet.nerd.font)
    assertEquals(IconSet.unicode.iconForFamily("Hack"), "Aa")

  test("the logo is ASCII-only in ascii mode"):
    assertEquals(IconSet.ascii.logo, "[NF]")
    assertEquals(IconSet.unicode.logo, "✦ NF ✦")

  test("icon modes parse case-insensitively after trimming"):
    assertEquals(IconMode.parse(" NERD "), Right(IconMode.Nerd))
    assertEquals(IconMode.parse("auto"), Right(IconMode.Auto))
    assertEquals(IconMode.parse("Unicode"), Right(IconMode.Unicode))
    assertEquals(IconMode.parse("ascii"), Right(IconMode.Ascii))

  test("an invalid icon mode renders the Go message with the raw value quoted"):
    assertEquals(
      IconMode.parse(" bogus ").left.map(_.render),
      Left("invalid --icons \" bogus \"; use auto, nerd, unicode, or ascii"),
    )

  test("icon modes render their flag spelling"):
    assertEquals(IconMode.values.toVector.map(_.render), Vector("auto", "nerd", "unicode", "ascii"))

  test("family hints follow the Go table, first match wins"):
    assertEquals(FamilyHint.of("JetBrainsMono"), "monospace favorite")
    assertEquals(FamilyHint.of("FiraCode"), "coding ligatures")
    assertEquals(FamilyHint.of("SymbolsOnly"), "glyph toolkit")
    assertEquals(FamilyHint.of("Hack"), "Nerd Font patched")
    assertEquals(FamilyHint.of("CodeMono"), "monospace favorite")

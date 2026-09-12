package io.worxbend.nerdfonts.fonts

import zio.test.Gen
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
import zio.test.check

/**
 * A property: anything `FamilyName` accepts is a benign single path component. This is the invariant the
 * filesystem and URL code rely on.
 */
object FamilyNamePropertySuite extends ZIOSpecDefault:
  // Arbitrary strings rarely contain the interesting bytes, so half the inputs are drawn from a hazardous
  // alphabet where separators, dots and NUL are common.
  private val hazardousChar: Gen[Any, Char] =
    Gen.elements('a', 'Z', '0', '/', '\\', '.', '\u0000', ' ', '~', '-', '_', 'é')

  private val candidate: Gen[Any, String] = Gen.oneOf(
    Gen.string,
    Gen.listOf(hazardousChar).map(_.mkString),
    Gen.elements("Hack", "", ".", "..", "../x", "..\\x", "/abs", "a/b", "x\u0000y", "Symbols Nerd Font"),
  )

  def spec = suite("FamilyNameProperty")(
    test("every accepted family name is a benign single path component"):
      check(candidate): raw =>
        FamilyName.parse(raw) match
          case Left(_)     => assertTrue(true)
          case Right(name) => assertTrue(isBenign(name.value)),
    test("an accepted family name round-trips unchanged"):
      check(candidate): raw =>
        FamilyName.parse(raw) match
          case Left(_)     => assertTrue(true)
          case Right(name) => assertTrue(name.value == raw),
  )

  private def isBenign(value: String): Boolean = value.nonEmpty &&
    value != "." && value != ".." &&
    !value.contains('/') && !value.contains('\\') && !value.contains('\u0000') &&
    !value.startsWith("/") &&
    value.split('/').lastOption.contains(value)

package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.FamilyNameError
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.picker.Fixtures.*

/** The selection → `InstallConfig` mapping, the picker's one crossing of the `FamilyName` boundary. */
final class PickerOutcomeSuite extends munit.FunSuite:

  test("a selection becomes a config with sorted, validated families"):
    val outcome =
      PickerOutcome.of(latest, Set("JetBrainsMono", "FiraCode"), destination, RefreshFontCache.Enabled)
    outcome match
      case PickerOutcome.Selected(config) =>
        assertEquals(config.families.map(_.value), Vector("FiraCode", "JetBrainsMono"))
        assertEquals(config.selector, ReleaseSelector.Tagged(latest.tag))
        assertEquals(config.destination, destination)
        assertEquals(config.refreshFontCache, RefreshFontCache.Enabled)
      case other                          => fail(s"expected Selected, got $other")

  test("finishing with nothing selected is a cancellation"):
    assertEquals(
      PickerOutcome.of(latest, Set.empty, destination, RefreshFontCache.Enabled),
      PickerOutcome.Cancelled,
    )

  test("an unsafe stem is rejected with the Go message"):
    val outcome = PickerOutcome.of(latest, Set("Hack", "../x"), destination, RefreshFontCache.Enabled)
    assertEquals(
      outcome,
      PickerOutcome.Rejected(ConfigValidationError.InvalidFamily(FamilyNameError.Unsafe("../x"))),
    )
    outcome match
      case PickerOutcome.Rejected(cause) => assertEquals(cause.render, "unsafe font family name \"../x\"")
      case other                         => fail(s"expected Rejected, got $other")

  test("the model maps a finished session through the same rule"):
    val done = press(atFamilies, PickerKey.Space, PickerKey.Down, PickerKey.Space, PickerKey.Enter)
    assertEquals(
      done.outcome.collect { case PickerOutcome.Selected(config) => config.families },
      Some(Vector("Hack", "JetBrainsMono").flatMap(FamilyName.parse(_).toOption)),
    )

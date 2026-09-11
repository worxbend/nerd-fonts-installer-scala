package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.picker.Fixtures.*
import io.worxbend.nerdfonts.picker.PickerKey.*

import ox.discard

/** Every rule of the §7 key precedence table, driven through the pure model. */
final class PickerModelSuite extends munit.FunSuite:

  test("enter on the release step opens the families of the highlighted release"):
    val step = familiesStep(press(model(), Enter))
    assertEquals(step.release, latest)
    assertEquals(step.selected, Set.empty[String])
    assertEquals(step.families.items.map(_.value), latest.families)

  test("enter on the release step follows the cursor"):
    assertEquals(familiesStep(press(model(), Down, Enter)).release, previous)

  test("space toggles the highlighted family on"):
    assertEquals(familiesStep(press(atFamilies, Space)).selected, Set("Hack"))

  test("space twice toggles the same family off"):
    assertEquals(familiesStep(press(atFamilies, Space, Space)).selected, Set.empty[String])

  test("a selects every family when not all are selected"):
    assertEquals(familiesStep(press(atFamilies, Space, Char('a'))).selected, latest.families.toSet)

  test("a clears the selection when every family is selected"):
    assertEquals(familiesStep(press(atFamilies, Char('a'), Char('a'))).selected, Set.empty[String])

  test("toggling refreshes the checkbox marker in the list"):
    val step = familiesStep(press(atFamilies, Space))
    assert(step.families.items.head.title.startsWith("☑"), step.families.items.head.title)
    assert(step.families.items(1).title.startsWith("☐"), step.families.items(1).title)

  test("enter on the families step with nothing selected is a no-op"):
    val next = press(atFamilies, Enter)
    assert(next.step.isInstanceOf[PickerStep.ChooseFamilies], next.step.toString)
    assertEquals(next.outcome, None)

  test("enter with a selection finishes the picker"):
    val next = press(atFamilies, Space, Enter)
    assertEquals(next.step, PickerStep.Done(latest, Set("Hack")))
    assert(next.outcome.exists(_.isInstanceOf[PickerOutcome.Selected]), next.outcome.toString)

  test("keys after the picker finished are ignored"):
    val done = press(atFamilies, Space, Enter)
    assertEquals(press(done, Char('q')).step, done.step)

  test("esc on the families step goes back to the release step"):
    assertEquals(press(atFamilies, Escape).step, PickerStep.ChooseRelease)

  test("b on the families step goes back to the release step"):
    assertEquals(press(atFamilies, Char('b')).step, PickerStep.ChooseRelease)

  test("going back keeps the release cursor where it was"):
    val back = press(model(), Down, Enter, Escape)
    assertEquals(back.releaseList.cursor, 1)

  test("re-entering the families step starts with nothing selected"):
    val again = press(atFamilies, Space, Escape, Enter)
    assertEquals(familiesStep(again).selected, Set.empty[String])

  test("esc on the release step cancels"):
    assertEquals(press(model(), Escape).outcome, Some(PickerOutcome.Cancelled))

  test("q cancels on the release step"):
    assertEquals(press(model(), Char('q')).outcome, Some(PickerOutcome.Cancelled))

  test("ctrl-c cancels on the families step"):
    assertEquals(press(atFamilies, CtrlC).outcome, Some(PickerOutcome.Cancelled))

  test("q while filtering cancels instead of being typed"):
    val filtering = press(atFamilies, Char('/'), Char('H'))
    assertEquals(press(filtering, Char('q')).outcome, Some(PickerOutcome.Cancelled))

  test("esc while filtering on the release step cancels and leaves the filter text alone"):
    val next = press(model(), Char('/'), Char('3'), Char('.'), Escape)
    assertEquals(next.outcome, Some(PickerOutcome.Cancelled))
    assertEquals(next.releaseList.filter, FilterState.Editing("3."))

  test("esc while filtering on the families step goes back without clearing the release filter"):
    val next = press(model(), Char('/'), Char('3'), Up, Enter, Char('/'), Char('H'), Escape)
    assertEquals(next.step, PickerStep.ChooseRelease)
    assertEquals(next.releaseList.filter, FilterState.Applied("3"))

  test("enter while filtering on the release step chooses the first fuzzy match"):
    val next = press(model(), Char('/'), Char('3'), Char('.'), Char('3'), Enter)
    assertEquals(familiesStep(next).release, previous)

  test("enter while filtering with nothing selected is a no-op"):
    val next = press(atFamilies, Char('/'), Char('H'), Enter)
    val step = familiesStep(next)
    assertEquals(step.families.filter, FilterState.Editing("H"))
    assertEquals(next.outcome, None)

  test("space while filtering toggles the highlighted family and is not inserted"):
    val step = familiesStep(press(atFamilies, Char('/'), Char('J'), Space))
    assertEquals(step.selected, Set("JetBrainsMono"))
    assertEquals(step.families.filter, FilterState.Editing("J"))

  test("a while filtering selects everything and is not inserted"):
    val step = familiesStep(press(atFamilies, Char('/'), Char('J'), Char('a')))
    assertEquals(step.selected, latest.families.toSet)
    assertEquals(step.families.filter, FilterState.Editing("J"))

  test("b while filtering goes back and is not inserted"):
    assertEquals(press(atFamilies, Char('/'), Char('J'), Char('b')).step, PickerStep.ChooseRelease)

  test("space while filtering the release list is typed, because it is not a release-step key"):
    assertEquals(press(model(), Char('/'), Char('v'), Space).releaseList.filter, FilterState.Editing("v "))

  test("up with a non-empty filter applies it"):
    val step = familiesStep(press(atFamilies, Char('/'), Char('J'), Up))
    assertEquals(step.families.filter, FilterState.Applied("J"))
    assertEquals(step.families.visible.map(_.item.value), Vector("JetBrainsMono"))

  test("down with a non-empty filter applies it"):
    assertEquals(
      familiesStep(press(atFamilies, Char('/'), Char('J'), Down)).families.filter,
      FilterState.Applied("J"),
    )

  test("tab, shift-tab, ctrl-k and ctrl-j apply the filter"):
    Vector(Tab, ShiftTab, CtrlK, CtrlJ).foreach: key =>
      assertEquals(
        familiesStep(press(atFamilies, Char('/'), Char('J'), key)).families.filter,
        FilterState.Applied("J"),
      )

  test("applying a filter that matches nothing clears it"):
    val step = familiesStep(press(atFamilies, Char('/'), Char('z'), Char('z'), Up))
    assertEquals(step.families.filter, FilterState.Inactive)
    assertEquals(step.families.visible.size, 3)

  test("leaving the input with an empty pattern clears the filter"):
    assertEquals(familiesStep(press(atFamilies, Char('/'), Down)).families.filter, FilterState.Inactive)

  test("typing filters live over title, description and value"):
    val typed = familiesStep(press(atFamilies, Char('/'), Char('J'), Char('e'), Char('t')))
    assertEquals(typed.families.filter, FilterState.Editing("Jet"))
    assertEquals(typed.families.visible.map(_.item.value), Vector("JetBrainsMono"))

  test("backspace edits the pattern and widens the list again"):
    val erased = familiesStep(press(atFamilies, Char('/'), Char('F'), Char('i'), Backspace, Backspace))
    assertEquals(erased.families.filter, FilterState.Editing(""))
    assertEquals(erased.families.visible.size, 3)

  test("slash opens the filter with the cursor reset to the first item"):
    val step = familiesStep(press(atFamilies, Down, Down, Char('/')))
    assertEquals(step.families.cursor, 0)
    assertEquals(step.families.filter, FilterState.Editing(""))

  test("an applied filter persists until reopened and emptied"):
    val applied  = familiesStep(press(atFamilies, Char('/'), Char('J'), Up, Down, Char('k')))
    assertEquals(applied.families.filter, FilterState.Applied("J"))
    val reopened = familiesStep(press(atFamilies, Char('/'), Char('J'), Up, Char('/')))
    assertEquals(reopened.families.filter, FilterState.Editing("J"))
    val emptied  = familiesStep(press(atFamilies, Char('/'), Char('J'), Up, Char('/'), Backspace, Up))
    assertEquals(emptied.families.filter, FilterState.Inactive)

  test("j and k move the cursor and do not wrap"):
    assertEquals(familiesStep(press(atFamilies, Char('j'), Char('j'), Char('j'))).families.cursor, 2)
    assertEquals(familiesStep(press(atFamilies, Char('j'), Char('k'), Char('k'))).families.cursor, 0)

  test("arrow keys move the cursor"):
    assertEquals(familiesStep(press(atFamilies, Down, Down, Up)).families.cursor, 1)

  test("g and G jump to the first and last item"):
    assertEquals(familiesStep(press(atFamilies, Char('G'))).families.cursor, 2)
    assertEquals(familiesStep(press(atFamilies, Char('G'), Char('g'))).families.cursor, 0)

  test("home and end jump to the first and last item"):
    assertEquals(familiesStep(press(atFamilies, End)).families.cursor, 2)
    assertEquals(familiesStep(press(atFamilies, End, Home)).families.cursor, 0)

  test("page keys move by a page while browsing"):
    val many = Vector(latest.copy(families = (1 to 30).map(i => s"Family$i").toVector))
    val page = model(many).layout.itemsPerPage
    assertEquals(familiesStep(press(model(many), Enter, PageDown)).families.cursor, page)
    assertEquals(familiesStep(press(model(many), Enter, PageDown, PageDown, PageUp)).families.cursor, page)

  test("page keys are disabled while the filter input is focused"):
    val many = Vector(latest.copy(families = (1 to 30).map(i => s"Family$i").toVector))
    assertEquals(familiesStep(press(model(many), Enter, Char('/'), PageDown)).families.cursor, 0)

  test("browsing letters are typed while the filter input is focused"):
    assertEquals(
      familiesStep(press(atFamilies, Char('/'), Char('j'), Char('k'))).families.filter,
      FilterState.Editing("jk"),
    )

  test("resizing re-settles the list window for the new page size"):
    val many     = Vector(latest.copy(families = (1 to 30).map(i => s"Family$i").toVector))
    val scrolled = press(model(many, viewport = Viewport(96, 60)), Enter, End)
    val shrunk   = scrolled.resized(Viewport(96, 24))
    val page     = shrunk.layout.itemsPerPage
    assertEquals(familiesStep(shrunk).families.windowStart(page), 30 - page)

  test("current release falls back to the newest when the filter hides everything"):
    assertEquals(press(model(), Char('/'), Char('z')).currentRelease, latest)

  test("the initial model requires at least one release"):
    intercept[IllegalArgumentException](model(Vector.empty)).discard

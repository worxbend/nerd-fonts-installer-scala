package io.worxbend.nerdfonts.picker

/** Filtering, ranking and the scrolling window of the immutable list. */
final class ListStateSuite extends munit.FunSuite:
  private def item(value: String): ListItem = ListItem(value, s"about $value", value)

  private val fonts = ListState(Vector("JetBrainsMono", "Hack", "Monaspace", "FiraCode").map(item))
  private val many  = ListState((1 to 10).map(i => item(s"Item$i")).toVector)

  test("moving down and up clamps at both ends"):
    assertEquals(fonts.moveUp.cursor, 0)
    assertEquals(fonts.moveDown.moveDown.moveDown.moveDown.moveDown.cursor, 3)

  test("paging moves by the page size and clamps"):
    assertEquals(many.pageDown(4).cursor, 4)
    assertEquals(many.pageDown(4).pageDown(4).pageDown(4).cursor, 9)
    assertEquals(many.pageDown(4).pageUp(4).pageUp(4).cursor, 0)

  test("first and last jump to the ends"):
    assertEquals(many.last.cursor, 9)
    assertEquals(many.last.first.cursor, 0)

  test("the fuzzy filter is a case-insensitive subsequence ranked by first position then span"):
    val filtered = fonts.openFilter.typeChar('m').typeChar('o').typeChar('n').typeChar('o')
    assertEquals(filtered.visible.map(_.item.value), Vector("Monaspace", "JetBrainsMono"))

  test("matched positions index the filter value, starting with the title"):
    val filtered = fonts.openFilter.typeChar('h').typeChar('k')
    assertEquals(
      filtered.visible.map(entry => (entry.item.value, entry.matched)),
      Vector(("Hack", Vector(0, 3))),
    )

  test("a tighter match outranks a looser one that starts at the same position"):
    val list = ListState(Vector(item("abxxc"), item("abc")))
    assertEquals(
      list.openFilter.typeChar('a').typeChar('b').typeChar('c').visible.map(_.item.value),
      Vector("abc", "abxxc"),
    )

  test("ties keep their original order"):
    val list = ListState(Vector(item("Hack"), item("Hasklig")))
    assertEquals(
      list.openFilter.typeChar('h').typeChar('a').visible.map(_.item.value),
      Vector("Hack", "Hasklig"),
    )

  test("editing the pattern resets the cursor to the first match"):
    assertEquals(fonts.moveDown.moveDown.openFilter.typeChar('f').cursor, 0)

  test("selected is the visible item under the cursor"):
    assertEquals(fonts.openFilter.typeChar('f').selected.map(_.value), Some("FiraCode"))
    assertEquals(fonts.openFilter.typeChar('z').selected, None)

  test("applying a matching pattern keeps it as an applied filter"):
    assertEquals(fonts.openFilter.typeChar('f').applyFilter.filter, FilterState.Applied("f"))

  test("applying an empty or unmatched pattern clears the filter"):
    assertEquals(fonts.openFilter.applyFilter.filter, FilterState.Inactive)
    assertEquals(fonts.openFilter.typeChar('z').applyFilter.filter, FilterState.Inactive)

  test("reopening an applied filter keeps its text for editing"):
    assertEquals(fonts.openFilter.typeChar('f').applyFilter.openFilter.filter, FilterState.Editing("f"))

  test("backspace on an empty pattern is a no-op"):
    assertEquals(fonts.openFilter.eraseChar.filter, FilterState.Editing(""))

  test("replacing items keeps the cursor and filter and clamps the cursor"):
    val replaced = fonts.last.withItems(Vector(item("Only")))
    assertEquals(replaced.cursor, 0)
    val kept     = fonts.moveDown.openFilter
      .typeChar('a')
      .applyFilter
      .moveDown
      .withItems(fonts.items.map(i => i.copy(title = "x " + i.title)))
    assertEquals(kept.cursor, 1)
    assertEquals(kept.filter, FilterState.Applied("a"))

  test("the window follows the cursor downwards with the smallest move"):
    val moved = (1 to 5).foldLeft(many)((list, _) => list.moveDown.scrolled(3))
    assertEquals(moved.windowStart(3), 3)
    assertEquals(moved.visibleItems(3).map(_.item.value), Vector("Item4", "Item5", "Item6"))

  test("the window follows the cursor back upwards"):
    val down = (1 to 5).foldLeft(many)((list, _) => list.moveDown.scrolled(3))
    val up   = (1 to 3).foldLeft(down)((list, _) => list.moveUp.scrolled(3))
    assertEquals(up.windowStart(3), 2)

  test("the window never starts past the last full page"):
    assertEquals(many.last.windowStart(4), 6)
    assertEquals(many.last.visibleItems(4).map(_.item.value), Vector("Item7", "Item8", "Item9", "Item10"))

  test("a page size larger than the list shows everything from the top"):
    assertEquals(many.last.windowStart(50), 0)
    assertEquals(many.last.visibleItems(50).size, 10)

  test("handle routes browsing keys and filter keys by input focus"):
    assertEquals(fonts.handle(PickerKey.Char('j'), 3).cursor, 1)
    assertEquals(
      fonts.handle(PickerKey.Char('/'), 3).handle(PickerKey.Char('j'), 3).filter,
      FilterState.Editing("j"),
    )

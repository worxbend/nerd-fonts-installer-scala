package io.worxbend.nerdfonts.picker

/**
 * The filter input's state. `Editing` and `Applied` both narrow the list; only `Editing` receives typed
 * characters, and only an applied filter shows in the status bar as `“text”`.
 */
private[picker] enum FilterState:
  case Inactive
  case Editing(text: String)
  case Applied(text: String)

  /** The pattern currently narrowing the list; empty when inactive. */
  def pattern: String = this match
    case Inactive      => ""
    case Editing(text) => text
    case Applied(text) => text

/** An item that survived the filter, with the matched positions the view underlines. */
final private[picker] case class FilteredItem(item: ListItem, matched: Vector[Int])

/**
 * An immutable, filterable, scrollable list: the bubbles `list.Model` reduced to what the picker uses.
 *
 * Every operation is a pure function returning the next state. The cursor indexes the *visible* (filtered,
 * ranked) items, so it is reset whenever the filter text changes and clamped whenever the items change. The
 * window offset follows the cursor with the smallest move that keeps it on screen, so browsing with `j`/`k`
 * scrolls one row at a time rather than flipping pages.
 */
final private[picker] case class ListState(
    items: Vector[ListItem],
    cursor: Int = 0,
    filter: FilterState = FilterState.Inactive,
    offset: Int = 0,
):
  /** The items the filter lets through, in rank order (original order when the filter is empty). */
  lazy val visible: Vector[FilteredItem] = FuzzyMatcher
    .rank(filter.pattern, items)(_.filterValue)
    .map((item, found) => FilteredItem(item, found.positions))

  def selected: Option[ListItem] = visible.lift(cursor).map(_.item)

  /** The applied precedence-table rules for keys the model did not consume. */
  def handle(key: PickerKey, pageSize: Int): ListState = filter match
    case FilterState.Editing(_) => handleWhileEditing(key)
    case _                      => handleWhileBrowsing(key, pageSize).scrolled(pageSize)

  def moveUp: ListState                = moveTo(cursor - 1)
  def moveDown: ListState              = moveTo(cursor + 1)
  def pageUp(pageSize: Int): ListState = moveTo(cursor - pageSize)

  def pageDown(pageSize: Int): ListState = moveTo(cursor + pageSize)
  def first: ListState                   = moveTo(0)
  def last: ListState                    = moveTo(visible.size - 1)

  /** `/`: focus the filter input, keeping an applied pattern so it can be edited, and start from the top. */
  def openFilter: ListState = copy(cursor = 0, offset = 0, filter = FilterState.Editing(filter.pattern))

  def typeChar(char: Char): ListState = edit(_ + char)

  def eraseChar: ListState = edit(_.dropRight(1))

  /**
   * Leave the input: a non-empty pattern that still matches something is kept as an applied filter; an empty
   * pattern, or one that matches nothing, clears the filter instead (bubbles' `AcceptWhileFiltering`).
   */
  def applyFilter: ListState = filter match
    case FilterState.Editing(text) if text.nonEmpty && visible.nonEmpty =>
      copy(filter = FilterState.Applied(text))
    case FilterState.Editing(_)                                         => copy(filter = FilterState.Inactive)
    case _                                                              => this

  /** Replace the items (a toggled marker) while keeping the cursor on the same row and the filter as is. */
  def withItems(replacement: Vector[ListItem]): ListState =
    val next = copy(items = replacement)
    next.copy(cursor = ListState.clamp(cursor, next.visible.size))

  /** The first visible row of a `pageSize`-row window that contains the cursor, moving as little as possible. */
  def windowStart(pageSize: Int): Int =
    val rows     = math.max(1, pageSize)
    val maxStart = math.max(0, visible.size - rows)
    val start    = math.min(offset, maxStart)
    if cursor < start then cursor
    else if cursor >= start + rows then cursor - rows + 1
    else start

  /** The rows on screen for a `pageSize`-row window. */
  def visibleItems(pageSize: Int): Vector[FilteredItem] =
    visible.slice(windowStart(pageSize), windowStart(pageSize) + math.max(1, pageSize))

  /** Remember where the window settled so the next movement scrolls from there. */
  def scrolled(pageSize: Int): ListState = copy(offset = windowStart(pageSize))

  // Left/Right and (where nothing higher in the precedence table has already claimed it) `b` mirror bubbles'
  // default keymap (`PrevPage: left, pgup, b`; `NextPage: right, pgdown`), which the model forwards every
  // unconsumed key into; on the families step `b` is a step key ("go back") and never reaches here.
  private def handleWhileBrowsing(key: PickerKey, pageSize: Int): ListState = key match
    case PickerKey.Up | PickerKey.Char('k')                      => moveUp
    case PickerKey.Down | PickerKey.Char('j')                    => moveDown
    case PickerKey.PageUp | PickerKey.Left | PickerKey.Char('b') => pageUp(pageSize)
    case PickerKey.PageDown | PickerKey.Right                    => pageDown(pageSize)
    case PickerKey.Home | PickerKey.Char('g')                    => first
    case PickerKey.End | PickerKey.Char('G')                     => last
    case PickerKey.Char('/')                                     => openFilter
    case _                                                       => this

  // Paging keys are deliberately dead while the input is focused, as in bubbles; `Enter` is never an accept
  // key here because the model consumes it first.
  private def handleWhileEditing(key: PickerKey): ListState = key match
    case PickerKey.Backspace                     => eraseChar
    case PickerKey.Space                         => typeChar(' ')
    case PickerKey.Char(char) if !char.isControl => typeChar(char)
    case PickerKey.Up | PickerKey.Down | PickerKey.Tab | PickerKey.ShiftTab | PickerKey.CtrlK |
        PickerKey.CtrlJ => applyFilter
    case _                                       => this

  private def edit(change: String => String): ListState = filter match
    case FilterState.Editing(text) => copy(cursor = 0, offset = 0, filter = FilterState.Editing(change(text)))
    case _                         => this

  private def moveTo(index: Int): ListState = copy(cursor = ListState.clamp(index, visible.size))

private[picker] object ListState:
  private def clamp(index: Int, size: Int): Int = math.max(0, math.min(index, size - 1))

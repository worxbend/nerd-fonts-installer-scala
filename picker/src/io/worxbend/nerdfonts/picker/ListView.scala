package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode

/**
 * Renders a `ListState` into exactly `height` rows of `width` cells: the bubbles list view rebuilt with a
 * fixed row budget. Rows: title (or the filter input), rule, status, blank, the item region, pagination.
 * Each item is a title row, a description row and a spacer, so the item region holds
 * `(rows + 1) / 3` items.
 */
private[picker] object ListView:
  /** Rows the title bar, status bar and pagination take from the list height. */
  val chromeRows: Int = 5

  /** Rows one item occupies including its spacer. */
  val rowsPerItem: Int = 3

  /** Page indicators switch from dots to `p/N` above this many pages, as bubbles does when dots stop fitting. */
  val maxDotPages: Int = 10

  def itemsPerPage(height: Int): Int = math.max(1, (height - chromeRows + 1) / rowsPerItem)

  /** Names used by the status bar: `1 font`, `12 fonts`. */
  final case class ItemNames(singular: String, plural: String):
    def count(n: Int): String = s"$n ${if n == 1 then singular else plural}"

  final case class Spec(title: String, names: ItemNames, width: Int, height: Int)

  def render(list: ListState, spec: Spec, colours: ColourMode): Vector[String] =
    val perPage = itemsPerPage(spec.height)
    val region  = itemRegion(list, perPage, spec, colours)
    val body    =
      Vector(titleRow(list, spec, colours), rule(spec, colours), status(list, spec.names, colours), "") ++
        padded(region, spec.height - chromeRows) :+ pagination(list, perPage, colours)
    body.map(TextWidth.truncate(_, spec.width))

  private def titleRow(list: ListState, spec: Spec, colours: ColourMode): String = list.filter match
    case FilterState.Editing(text) => Palette.paint("Filter: ", Styles.filterPrompt, colours) + text + Palette
        .paint("█", Styles.filterPrompt, colours)
    case _                         => Palette.paint(s" ${spec.title} ", Styles.listTitle, colours)

  private def rule(spec: Spec, colours: ColourMode): String =
    Palette.paint("─" * spec.width, Styles.listRule, colours)

  // bubbles' statusView, including the `“filter” N items • M filtered` shape.
  private def status(list: ListState, names: ItemNames, colours: ColourMode): String =
    val shown    = list.visible.size
    val filtered = list.items.size - shown
    val main     = list.filter match
      case FilterState.Editing(_) if shown == 0 => "Nothing matched"
      case FilterState.Editing(_)               => names.count(shown)
      case _ if list.items.isEmpty              => s"No ${names.plural}"
      case FilterState.Applied(text)            =>
        Palette.paint(s"“${TextWidth.truncate(text.trim, 10)}” ", Styles.activeFilter, colours) + names.count(
          shown,
        )
      case FilterState.Inactive                 => names.count(shown)
    val suffix   =
      if filtered > 0 then " • " + Palette.paint(s"$filtered filtered", Styles.filterCount, colours) else ""
    Palette.paint(main, Styles.statusBar, colours) + suffix

  /** Whether an item row is the one under the cursor; it changes the marker and the background. */
  private enum Row:
    case Highlighted, Normal

  private def itemRegion(list: ListState, perPage: Int, spec: Spec, colours: ColourMode): Vector[String] =
    val start = list.windowStart(perPage)
    list
      .visibleItems(perPage)
      .zipWithIndex
      .flatMap: (entry, index) =>
        val row = if start + index == list.cursor then Row.Highlighted else Row.Normal
        Vector(
          itemTitle(entry, row, spec.width, colours),
          itemDescription(entry, row, spec.width, colours),
          "",
        )
      .dropRight(1)

  private def itemTitle(entry: FilteredItem, row: Row, width: Int, colours: ColourMode): String =
    val base = row match
      case Row.Highlighted => Styles.selectedTitle
      case Row.Normal      => Styles.normalTitle
    prefix(row, colours) + highlighted(entry.item.title, entry.matched, base, width - 2, colours)

  private def itemDescription(entry: FilteredItem, row: Row, width: Int, colours: ColourMode): String =
    val base = row match
      case Row.Highlighted => Styles.selectedDesc
      case Row.Normal      => Styles.normalDesc
    prefix(row, colours) + Palette.paint(TextWidth.truncate(entry.item.description, width - 2), base, colours)

  private def prefix(row: Row, colours: ColourMode): String = row match
    case Row.Highlighted => Palette.paint("┃", Styles.selectedMarker, colours) + " "
    case Row.Normal      => "  "

  // Runs of matched / unmatched characters are painted separately so the underline lands on the glyphs the
  // filter hit; positions come from the filter value, whose prefix is the title.
  private def highlighted(
      title: String,
      matched: Vector[Int],
      base: fansi.Attrs,
      width: Int,
      colours: ColourMode,
  ): String =
    val visible = TextWidth.truncate(title, width)
    val hits    = matched.filter(_ < visible.length).toSet
    visible.zipWithIndex
      .foldLeft(Vector.empty[(Boolean, String)]): (runs, pair) =>
        val (char, index) = pair
        runs.lastOption match
          case Some((hit, text)) if hit == hits(index) => runs.init :+ (hit, text + char)
          case _                                       => runs :+ (hits(index), char.toString)
      .map((hit, text) => Palette.paint(text, if hit then Styles.filterMatch else base, colours))
      .mkString

  private def pagination(list: ListState, perPage: Int, colours: ColourMode): String =
    val pages = math.max(1, (list.visible.size + perPage - 1) / perPage)
    val page  = math.min(pages - 1, list.cursor / perPage)
    if pages <= 1 then ""
    else if pages <= maxDotPages then
      (0 until pages)
        .map(i =>
          if i == page then Palette.paint("●", Styles.paginationActive, colours)
          else Palette.paint("○", Styles.pagination, colours),
        )
        .mkString(" ")
    else Palette.paint(s"${page + 1}/$pages", Styles.pagination, colours)

  private def padded(rows: Vector[String], height: Int): Vector[String] =
    rows.take(height) ++ Vector.fill(math.max(0, height - rows.size))("")

package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.TerminalSafe
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.releases.Release

/**
 * Where the picker is. Each step carries exactly the state it needs, so a families list cannot exist without
 * the release it was built from and a finished selection cannot be edited. `Cancelled` is a terminal step
 * rather than a flag so that `update` has nothing to check before every key.
 */
private[picker] enum PickerStep:
  case ChooseRelease
  case ChooseFamilies(release: Release, families: ListState, selected: Set[String])
  case Done(release: Release, selected: Set[String])
  case Cancelled

  /** Position in the release › families › install breadcrumb. */
  def index: Int = this match
    case ChooseRelease           => 0
    case ChooseFamilies(_, _, _) => 1
    case Done(_, _)              => 2
    case Cancelled               => 0

/**
 * The picker's whole state, and the pure `update` that drives it (the Go Bubble Tea model without the
 * runtime). The release list lives on the model rather than in the step so that going back from the
 * families step returns to the same cursor and filter.
 *
 * Keys are resolved in strict precedence, independent of whether a filter input is focused: cancel and back
 * first, then the step's own keys, then the list. That is what makes `q` un-typeable and `Esc` never clear
 * the filter.
 */
final private[picker] case class PickerModel private (
    releases: Vector[Release],
    releaseList: ListState,
    step: PickerStep,
    icons: IconSet,
    destination: DestinationPath,
    refreshFontCache: RefreshFontCache,
    viewport: Viewport,
):
  def layout: Layout = Layout(viewport)

  /** The session's verdict once a terminal step is reached; `None` while the user is still choosing. */
  def outcome: Option[PickerOutcome] = step match
    case PickerStep.Cancelled               => Some(PickerOutcome.Cancelled)
    case PickerStep.Done(release, selected) =>
      Some(PickerOutcome.of(release, selected, destination, refreshFontCache))
    case _                                  => None

  def update(key: PickerKey): PickerModel = step match
    case PickerStep.Done(_, _) | PickerStep.Cancelled => this
    case _                                            => updateGlobal(key)

  /** Adopt a new terminal size; the lists re-settle their windows for the new page size. */
  def resized(size: Viewport): PickerModel =
    val next = copy(viewport = size)
    val rows = next.layout.itemsPerPage
    next.copy(
      releaseList = releaseList.scrolled(rows),
      step = step match
        case families: PickerStep.ChooseFamilies => families.copy(families = families.families.scrolled(rows))
        case other                               => other,
    )

  /** The release the cursor is on, falling back to the newest when the filter hides everything. */
  def currentRelease: Release = releaseList.selected.flatMap(releaseFor).getOrElse(releases.head)

  def selectedCount: Int = step match
    case PickerStep.ChooseFamilies(_, _, selected) => selected.size
    case PickerStep.Done(_, selected)              => selected.size
    case _                                         => 0

  private def updateGlobal(key: PickerKey): PickerModel = key match
    case PickerKey.CtrlC | PickerKey.Char('q') => copy(step = PickerStep.Cancelled)
    case PickerKey.Escape                      => step match
        case PickerStep.ChooseFamilies(_, _, _) => copy(step = PickerStep.ChooseRelease)
        case _                                  => copy(step = PickerStep.Cancelled)
    case _                                     => step match
        case PickerStep.ChooseRelease            => updateReleaseStep(key)
        case families: PickerStep.ChooseFamilies => updateFamilyStep(families, key)
        case _                                   => this

  private def updateReleaseStep(key: PickerKey): PickerModel = key match
    case PickerKey.Enter => releaseList.selected.flatMap(releaseFor).fold(this)(enterFamilies)
    case other           => copy(releaseList = releaseList.handle(other, layout.itemsPerPage))

  private def updateFamilyStep(current: PickerStep.ChooseFamilies, key: PickerKey): PickerModel = key match
    case PickerKey.Char('b') => copy(step = PickerStep.ChooseRelease)
    case PickerKey.Space     => current.families.selected.fold(this)(item =>
        withSelection(current, toggled(current.selected, item.value)),
      )
    case PickerKey.Char('a') => withSelection(current, allOrNone(current))
    case PickerKey.Enter     =>
      if current.selected.isEmpty then this
      else copy(step = PickerStep.Done(current.release, current.selected))
    case other               => copy(step = current.copy(families = current.families.handle(other, layout.itemsPerPage)))

  private def enterFamilies(release: Release): PickerModel =
    copy(step = PickerStep.ChooseFamilies(release, ListState(familyItems(release, Set.empty)), Set.empty))

  private def withSelection(current: PickerStep.ChooseFamilies, selected: Set[String]): PickerModel =
    copy(step =
      current.copy(
        selected = selected,
        families = current.families.withItems(familyItems(current.release, selected)),
      ),
    )

  private def toggled(selected: Set[String], family: String): Set[String] =
    if selected(family) then selected - family else selected + family

  private def allOrNone(current: PickerStep.ChooseFamilies): Set[String] =
    val all = current.release.families.toSet
    if current.selected.size == all.size then Set.empty else all

  private def releaseFor(item: ListItem): Option[Release] = releases.find(_.tag.value == item.value)

  // `family` is an unvalidated release-asset stem (`FamilyName.parse` has not run yet) and `release.tag.value`
  // is unvalidated GitHub API text; both are sanitised only in the text that reaches the rendered frame
  // (`title`/`description`), never in `value`, which stays the exact stem so selection and, later,
  // `FamilyName.parse` still see what the release actually published.
  private[picker] def familyItems(release: Release, selected: Set[String]): Vector[ListItem] =
    val tag = TerminalSafe.sanitize(release.tag.value)
    release.families.map: family =>
      val marker = if selected(family) then icons.checked else icons.unchecked
      val safe   = TerminalSafe.sanitize(family)
      ListItem(
        title = s"$marker  ${icons.iconForFamily(family)}  $safe",
        description = s"${icons.release} $tag  ${icons.separator}  ${FamilyHint.of(family)}",
        value = family,
      )

private[picker] object PickerModel:
  /**
   * The model at the release step; `releases` must be non-empty, which `PickerSession` guarantees. The
   * primary constructor is private (and so, per Scala's rule for private-constructor case classes, is the
   * synthesized `copy`) so `initial` is the only way to reach an invalid, empty-`releases` model — a defect
   * this `require` still turns into a fast failure at the boundary rather than a later `NoSuchElementException`
   * from `currentRelease`.
   */
  def initial(
      releases: Vector[Release],
      destination: DestinationPath,
      refreshFontCache: RefreshFontCache,
      iconMode: IconMode,
      viewport: Viewport,
  ): PickerModel =
    require(releases.nonEmpty, "the picker needs at least one release")
    val icons = IconSet.forMode(iconMode)
    PickerModel(
      releases = releases,
      releaseList = ListState(releases.map(releaseItem(icons, _))),
      step = PickerStep.ChooseRelease,
      icons = icons,
      destination = destination,
      refreshFontCache = refreshFontCache,
      viewport = viewport,
    )

  private def releaseItem(icons: IconSet, release: Release): ListItem = ListItem(
    title = s"${icons.release} ${TerminalSafe.sanitize(release.tag.value)}",
    description =
      s"${icons.font}  ${release.families.size} font archives  ${icons.separator}  ${icons.toolbox} ready for terminals and editors",
    value = release.tag.value,
  )

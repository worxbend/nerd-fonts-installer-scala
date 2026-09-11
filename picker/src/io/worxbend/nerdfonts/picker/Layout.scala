package io.worxbend.nerdfonts.picker

/**
 * The Go layout budget, computed once per frame from the viewport.
 *
 * Everything that is not the list costs rows: the banner box (2 border + 2 padding + 5 content rows, or 3
 * when compact), two blank separators, the list panel's border and padding (4) and the footer. The list
 * height is what is left, so the rendered frame is exactly `safeHeight` rows tall; a frame even one row taller
 * than the terminal scrolls the banner's top border away. Terminals below the floors are laid out as if they
 * were 48×24, the smallest size the chrome fits in.
 */
final private[picker] case class Layout(viewport: Viewport):
  import Layout.*

  val safeWidth: Int  = math.max(minWidth, viewport.width)
  val safeHeight: Int = math.max(minHeight, viewport.height)

  def banner: BannerStyle = if safeHeight < compactBelowHeight then BannerStyle.Compact else BannerStyle.Full

  def arrangement: Arrangement = if safeWidth >= wideFromWidth then Arrangement.Wide else Arrangement.Narrow

  def bodyWidth: Int = math.min(maxBodyWidth, safeWidth)

  /** Go's `previewWidth`: the side panel's lipgloss width (content + padding, without the border). */
  def previewWidth: Int = arrangement match
    case Arrangement.Wide   => sidePanelWidth
    case Arrangement.Narrow => bodyWidth - 4

  /** Go's `listPanelWidth`: the list panel's lipgloss width (content + padding, without the border). */
  def listPanelWidth: Int = arrangement match
    case Arrangement.Wide   => bodyWidth - previewWidth - 8
    case Arrangement.Narrow => bodyWidth - 4

  /** Rows available to the list view inside its panel; never below bubbles' own floor. */
  def listHeight: Int =
    val chrome = banner match
      case BannerStyle.Full    => chromeHeight
      case BannerStyle.Compact => compactChromeHeight
    math.max(minListHeight, safeHeight - chrome)

  /** Total columns of the banner box, border included (Go: `bodyWidth - 6` content plus the two border cells). */
  def bannerWidth: Int = bodyWidth - 4

  /** Total columns of the list panel, border included. */
  def listPanelTotalWidth: Int = listPanelWidth + 2

  /** Total columns of the side panel, border included. */
  def previewTotalWidth: Int = previewWidth + 2

  /** How many two-line items (plus one spacer) the list's item region holds. */
  def itemsPerPage: Int = ListView.itemsPerPage(listHeight)

private[picker] object Layout:
  val chromeHeight: Int        = 16
  val compactChromeHeight: Int = 14
  val minListHeight: Int       = 9
  val minWidth: Int            = 48
  val minHeight: Int           = 24
  val maxBodyWidth: Int        = 132
  val sidePanelWidth: Int      = 34
  val wideFromWidth: Int       = 104
  val compactBelowHeight: Int  = 26

/** Whether the banner shows its subtitle and badge rows; dropped on short terminals so the frame still fits. */
private[picker] enum BannerStyle:
  case Full, Compact

/** Whether the side panel may sit beside the list; below 104 columns it would have to stack and is dropped. */
private[picker] enum Arrangement:
  case Wide, Narrow

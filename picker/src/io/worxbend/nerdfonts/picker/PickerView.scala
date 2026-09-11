package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode

/**
 * Turns a `PickerModel` into a `Frame`: the Go `View()` with its banner, list panel, optional side panel and
 * help footer. Pure, so the size matrix test can assert the height budget without a terminal. Colour is
 * decided solely by the `ColourMode` argument; the model never knows whether it is being drawn in colour.
 */
object PickerView:
  def render(model: PickerModel, colours: ColourMode): Frame = model.step match
    case PickerStep.ChooseRelease           => Frame(Screen(model, colours).release)
    case current: PickerStep.ChooseFamilies => Frame(Screen(model, colours).families(current))
    case done: PickerStep.Done              => Frame(Screen(model, colours).done(done))
    case PickerStep.Cancelled               => Frame.empty

  /** One frame's worth of rendering context; a class so the layout and icons are computed once per frame. */
  final private class Screen(model: PickerModel, colours: ColourMode):
    private val layout = model.layout
    private val icons  = model.icons

    def release: Vector[String] =
      val list = ListView.render(
        model.releaseList,
        listSpec(s"${icons.pkg}  Select Nerd Fonts release", "release", "releases"),
        colours,
      )
      screen(
        banner("Choose a release", "Pick the Nerd Fonts tag to browse, then filter or confirm."),
        body(list, releasePreview),
        help("enter" -> "choose release", "/" -> "filter", "esc/q" -> "quit"),
      )

    def families(current: PickerStep.ChooseFamilies): Vector[String] =
      val total = current.release.families.size
      val list  = ListView.render(
        current.families,
        listSpec(s"${icons.title}  Select font families", "font", "fonts"),
        colours,
      )
      screen(
        banner(
          "Build your font set",
          s"${current.selected.size}/$total selected for ${current.release.tag.value}",
        ),
        body(list, familyPreview(current)),
        help(
          "space" -> "toggle",
          "a"     -> "all/none",
          "enter" -> "install",
          "b/esc" -> "back",
          "/"     -> "filter",
          "q"     -> "quit",
        ),
      )

    def done(current: PickerStep.Done): Vector[String] =
      val count = current.selected.size
      val lines = Vector(
        paint(s"${icons.ready}  Selection locked in", Styles.success),
        Palette.gradientRule(layout.bannerWidth - Box.horizontalChrome, colours),
        Palette.statLine(icons.release, "Release", current.release.tag.value, colours),
        Palette.statLine(icons.folder, "Destination", model.destination.value, colours),
        Palette.statLine(icons.selected, "Families", count.toString, colours),
        "",
        Palette.gradientText(s"${icons.launch}  Launching the installer…", Palette.brandRamp, colours),
      )
      screen(
        banner("Ready to install", s"$count families selected"),
        Box.render(layout.bannerWidth, lines, Palette.cyan, colours),
        help("enter" -> "continue"),
      )

    private def screen(header: Vector[String], body: Vector[String], footer: String): Vector[String] =
      (header :+ "") ++ (body :+ "") :+ TextWidth.truncate(footer, layout.safeWidth)

    private def listSpec(title: String, singular: String, plural: String): ListView.Spec = ListView.Spec(
      title,
      ListView.ItemNames(singular, plural),
      width = layout.listPanelTotalWidth - Box.horizontalChrome,
      height = layout.listHeight,
    )

    // The side panel is dropped whenever it would not fit beside the list: a narrow terminal, or one where
    // its own wrapped text is taller than the list panel. Either would add rows the budget does not have.
    private def body(list: Vector[String], preview: Vector[String]): Vector[String] =
      val panel = Box.render(layout.listPanelTotalWidth, list, Palette.cyan, colours)
      layout.arrangement match
        case Arrangement.Wide if preview.size <= panel.size =>
          panel.zipWithIndex.map((line, index) => preview.lift(index).fold(line)(side => s"$line  $side"))
        case _                                              => panel

    private def banner(stepLabel: String, detail: String): Vector[String] =
      val textWidth = layout.bannerWidth - Box.horizontalChrome
      val wordmark  = Palette.gradientText(s"${icons.logo}  nerd-fonts-installer", Palette.brandRamp, colours)
      val header    = layout.arrangement match
        case Arrangement.Wide   => Palette.spread(textWidth, wordmark, breadcrumb)
        case Arrangement.Narrow => wordmark
      val core      = Vector(header, Palette.gradientRule(textWidth, colours), paint(stepLabel, Styles.title))
      val extra     = layout.banner match
        case BannerStyle.Compact => Vector.empty
        case BannerStyle.Full    => Vector(paint(detail, Styles.subtitle), badges)
      Box.render(layout.bannerWidth, core ++ extra, Palette.violet, colours)

    private def badges: String = Vector(
      paint(s" ${icons.pkg} CLI ", Styles.badgePkg),
      paint(s" ${icons.font} patched glyphs ", Styles.badgeFont),
      paint(s" ${icons.launch} terminal-ready ", Styles.badgeLaunch),
    ).mkString(" ")

    private def breadcrumb: String =
      val current = model.step.index
      Vector("release", "families", "install").zipWithIndex
        .map: (label, index) =>
          if index == current then paint(s"◉ $label", Styles.crumbActive)
          else if index < current then paint(s"✓ $label", Styles.crumbDone)
          else paint(s"○ $label", Styles.crumbTodo)
        .mkString(paint(" › ", Styles.crumbSeparator))

    private def panelTitle(label: String): String =
      paint("▌", Styles.accent) + " " + Palette.gradientText(label, Palette.brandRamp, colours)

    private def releasePreview: Vector[String] =
      val release = model.currentRelease
      val lines   = Vector(
        panelTitle(s"${icons.pkg} Release cockpit"),
        "",
        Palette.statLine(icons.release, "Current", release.tag.value, colours),
        Palette.statLine(icons.font, "Archives", release.families.size.toString, colours),
        Palette.statLine(icons.toolbox, "Mode", icons.mode.render, colours),
        "",
      ) ++ note("Use filtering to jump across releases. Press enter to open the selected archive catalog.")
      Box.render(layout.previewTotalWidth, lines, Palette.violet, colours)

    private def familyPreview(current: PickerStep.ChooseFamilies): Vector[String] =
      val total    = current.release.families.size
      val selected = current.selected.size
      val lines    = Vector(
        panelTitle(s"${icons.title} Install plan"),
        "",
        Palette.statLine(icons.release, "Release", current.release.tag.value, colours),
        Palette.statLine(icons.folder, "Destination", model.destination.value, colours),
        Palette.statLine(icons.selected, "Selected", s"$selected of $total", colours),
        "",
        Palette.progressBar(selected, total, colours),
        "",
      ) ++ note("Toggle families with space. Select all when bootstrapping a new terminal profile.")
      Box.render(layout.previewTotalWidth, lines, Palette.violet, colours)

    // Go's lipgloss wraps the side-panel note to the panel width; the same wrap keeps the fit check honest.
    private def note(text: String): Vector[String] =
      TextWidth.wrap(text, layout.previewTotalWidth - Box.horizontalChrome).map(paint(_, Styles.subtitle))

    private def help(entries: (String, String)*): String = entries
      .map((key, action) => paint(key, Styles.key) + paint(s": $action", Styles.help))
      .mkString(paint("  •  ", Styles.help))

    private def paint(text: String, style: fansi.Attrs): String = Palette.paint(text, style, colours)

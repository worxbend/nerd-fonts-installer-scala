package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.releases.Release

/** Shared releases and a keystroke helper for the picker suites. */
private[picker] object Fixtures:
  def tag(value: String): ReleaseTag = ReleaseTag.parse(value).getOrElse(sys.error(s"blank tag $value"))

  val latest: Release           = Release("v3.4.0", tag("v3.4.0"), Vector("Hack", "JetBrainsMono", "FiraCode"))
  val previous: Release         = Release("v3.3.0", tag("v3.3.0"), Vector("Hack"))
  val releases: Vector[Release] = Vector(latest, previous)

  val destination: DestinationPath = DestinationPath.parse("/tmp/fonts").getOrElse(sys.error("blank"))

  def model(
      releases: Vector[Release] = releases,
      icons: IconMode = IconMode.Auto,
      viewport: Viewport = Viewport(96, 32),
  ): PickerModel = PickerModel.initial(releases, destination, RefreshFontCache.Enabled, icons, viewport)

  /** Press keys in order; characters of a string are typed one by one. */
  def press(model: PickerModel, keys: PickerKey*): PickerModel = keys.foldLeft(model)(_.update(_))

  def typed(text: String): Vector[PickerKey] = text.toVector.map(PickerKey.Char(_))

  /** A model already on the families step of `latest`. */
  def atFamilies: PickerModel = press(model(), PickerKey.Enter)

  def familiesStep(model: PickerModel): PickerStep.ChooseFamilies = model.step match
    case step: PickerStep.ChooseFamilies => step
    case other                           => sys.error(s"expected the families step, got $other")

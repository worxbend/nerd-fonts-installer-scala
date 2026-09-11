package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.FamilyName
import io.worxbend.nerdfonts.fonts.FamilyNameError
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector
import io.worxbend.nerdfonts.releases.Release

/**
 * How a picker session ended. `Rejected` exists because release asset stems are untrusted upstream data: a
 * stem that fails `FamilyName.parse` must never become a path, and the CLI reports it exactly like an unsafe
 * config entry. Finishing with nothing selected is a cancellation, as in the Go reference.
 */
enum PickerOutcome:
  case Cancelled
  case Rejected(cause: ConfigValidationError)
  case Selected(config: InstallConfig)

object PickerOutcome:
  /**
   * The only place picker output crosses the `FamilyName` boundary: every selected stem is validated, sorted,
   * and the first failure wins.
   */
  def of(
      release: Release,
      selected: Set[String],
      destination: DestinationPath,
      refreshFontCache: RefreshFontCache,
  ): PickerOutcome =
    if selected.isEmpty then Cancelled
    else
      validated(selected.toVector.sorted) match
        case Left(error)     => Rejected(ConfigValidationError.InvalidFamily(error))
        case Right(families) => Selected(
            InstallConfig(ReleaseSelector.of(release.tag), destination, refreshFontCache, families),
          )

  private def validated(stems: Vector[String]): Either[FamilyNameError, Vector[FamilyName]] = stems
    .foldLeft[Either[FamilyNameError, Vector[FamilyName]]](Right(Vector.empty)): (accepted, stem) =>
      accepted.flatMap(names => FamilyName.parse(stem).map(names :+ _))

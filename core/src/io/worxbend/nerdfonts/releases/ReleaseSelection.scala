package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseSelector

/** Picks the release a selector refers to out of a catalogue listing (newest first, as GitHub returns them). */
object ReleaseSelection:
  def select(releases: Vector[Release], selector: ReleaseSelector): Either[ReleaseError, Release] =
    selector match
      case ReleaseSelector.Latest      => releases.headOption.toRight(ReleaseError.NoReleases)
      case ReleaseSelector.Tagged(tag) => releases.find(_.tag == tag).toRight(ReleaseError.NotFound(tag))

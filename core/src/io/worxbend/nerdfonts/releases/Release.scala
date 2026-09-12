package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.ReleaseTag

/**
 * One usable Nerd Fonts release: a display name, its tag and the font families it ships.
 *
 * `families` are raw asset stems, deliberately not `FamilyName`: `--font-names` prints them verbatim, as Go
 * does, and the catalogue never drops or rejects a stem. Configured installs cross the `FamilyName` trust
 * boundary through the config loader.
 */
final case class Release(name: String, tag: ReleaseTag, families: Vector[String])

/** Where releases come from; the production adapter is [[GitHubReleaseCatalogue]]. */
trait ReleaseCatalogue:
  def releases(): Either[ReleaseError, Vector[Release]]

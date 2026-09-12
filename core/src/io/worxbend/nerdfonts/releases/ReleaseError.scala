package io.worxbend.nerdfonts.releases

import io.worxbend.nerdfonts.fonts.Quoting
import io.worxbend.nerdfonts.fonts.ReleaseTag
import io.worxbend.nerdfonts.http.HttpError

/**
 * Why the catalogue could not produce a release. `NoReleases` and `NotFound` are user-correctable and map to
 * exit code 2 in the CLI; the other two are failures of the world and map to 1.
 */
enum ReleaseError:
  case NoReleases
  case NotFound(tag: ReleaseTag)
  case Http(cause: HttpError)
  case Decode(message: String)

  /** The user-facing wording, prefixes included. */
  def render: String = this match
    case NoReleases      => "no Nerd Fonts releases found"
    case NotFound(tag)   => s"nerd fonts release ${Quoting.quote(tag.value)} was not found"
    case Http(cause)     => s"list Nerd Fonts releases: ${cause.render}"
    case Decode(message) => s"decode Nerd Fonts releases: $message"

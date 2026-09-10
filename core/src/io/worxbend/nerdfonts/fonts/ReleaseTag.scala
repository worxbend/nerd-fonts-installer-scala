package io.worxbend.nerdfonts.fonts

/**
 * A Nerd Fonts release tag such as `v3.4.0`, trimmed and never blank.
 *
 * Tags come from two untrusted places, the config file and the GitHub API, and both treat a blank tag as
 * "no tag" rather than as an error, which is why the constructor yields an `Option`.
 */
opaque type ReleaseTag = String

object ReleaseTag:
  /** Trims the input and rejects a blank result; a tag is never normalised beyond that. */
  def parse(raw: String): Option[ReleaseTag] = Option(raw.trim).filter(_.nonEmpty)

  given Ordering[ReleaseTag] = Ordering.String

  extension (tag: ReleaseTag)
    /** The tag text exactly as GitHub knows it. */
    def value: String = tag

package io.worxbend.nerdfonts.fonts

/**
 * Which release to install from: the newest one or a specific tag.
 *
 * Kept as an enum rather than an optional tag because the two cases produce different download URL shapes
 * (`releases/latest/download/...` versus `releases/download/<tag>/...`) and every consumer must handle both.
 */
enum ReleaseSelector:
  case Latest
  case Tagged(tag: ReleaseTag)

  /** The configuration spelling: `latest` or the tag itself. */
  def render: String = this match
    case Latest      => ReleaseSelector.latestKeyword
    case Tagged(tag) => tag.value

object ReleaseSelector:
  /** The literal the Go reference compares against, case-sensitively. */
  val latestKeyword: String = "latest"

  /** A blank value or the keyword `latest` selects the newest release; anything else is a tag. */
  def parse(raw: String): ReleaseSelector = ReleaseTag.parse(raw) match
    case Some(tag) if tag.value == latestKeyword => Latest
    case Some(tag)                               => Tagged(tag)
    case None                                    => Latest

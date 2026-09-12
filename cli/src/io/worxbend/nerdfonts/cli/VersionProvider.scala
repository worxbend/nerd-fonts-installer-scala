package io.worxbend.nerdfonts.cli

/** Go's `"%s %s (%s, %s)\n"` from the constants Mill generated into `BuildInfo`; no reflection. */
private[cli] object VersionProvider:
  val line: String =
    s"nerd-fonts-installer ${BuildInfo.version} (${BuildInfo.commit}, ${BuildInfo.buildDate})"

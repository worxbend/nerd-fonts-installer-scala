package io.worxbend.nerdfonts.cli

/** The `"%s %s (%s, %s)\n"` line built from the constants Mill generated into `BuildInfo`; no reflection. */
private[cli] object VersionProvider:
  val line: String =
    s"nerd-fonts-installer ${BuildInfo.version} (${BuildInfo.commit}, ${BuildInfo.buildDate})"

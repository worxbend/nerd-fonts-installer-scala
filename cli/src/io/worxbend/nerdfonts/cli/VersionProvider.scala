package io.worxbend.nerdfonts.cli

import picocli.CommandLine

/** Go's `"%s %s (%s, %s)\n"` from the constants Mill generated; wired programmatically, so never reflected on. */
private[cli] object VersionProvider extends CommandLine.IVersionProvider:
  val line: String =
    s"nerd-fonts-installer ${BuildInfo.version} (${BuildInfo.commit}, ${BuildInfo.buildDate})"

  def getVersion(): Array[String] = Array(line)

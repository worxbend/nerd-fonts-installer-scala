package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.InstallConfig

/**
 * A config found by discovery together with the file it came from: the CLI prints `Using config <path>` only
 * for discovered files, and `InstallConfig` itself deliberately carries no origin.
 */
final case class DiscoveredConfig(path: os.Path, config: InstallConfig)

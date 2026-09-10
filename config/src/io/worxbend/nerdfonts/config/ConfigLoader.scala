package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.fonts.InstallConfig

import java.io.IOException
import java.nio.file.NoSuchFileException

import ox.either
import ox.either.catching
import ox.either.ok

/**
 * Turns a config file path into a validated `InstallConfig`: read, decode by extension, default, validate.
 *
 * This is the only place the three steps meet, so the explicit `--config`, the environment variable and every
 * discovered candidate are loaded by the same rules; discovery relies on `NotFound` being distinguishable from
 * every other failure.
 */
object ConfigLoader:
  def load(path: os.Path): Either[ConfigError, InstallConfig] = either:
    val text     = read(path).ok()
    val document = decode(path, text).ok()
    document.validated.left.map(ConfigError.Invalid(path, _)).ok()

  private def read(path: os.Path): Either[ConfigError, String] = os
    .read(path)
    .catching[IOException]
    .left
    .map:
      case _: NoSuchFileException => ConfigError.NotFound(path)
      case error                  => ConfigError.Unreadable(path, Diagnostics.describe(error))

  private def decode(path: os.Path, text: String): Either[ConfigError, ConfigDocument] =
    ConfigFormat.of(path) match
      case ConfigFormat.Json => JsonConfigDecoder.decode(path, text)
      case ConfigFormat.Yaml => YamlConfigDecoder.decode(path, text)

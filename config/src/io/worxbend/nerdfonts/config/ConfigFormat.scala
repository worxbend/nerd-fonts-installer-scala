package io.worxbend.nerdfonts.config

import java.util.Locale

/**
 * Which reader a file gets, decided by its last extension alone, compared case-insensitively:
 * `.json` -> JSON, `.yaml`/`.yml` -> YAML, `.conf`/`.hocon` -> HOCON. Any other extension — or none — is a
 * hard [[ConfigError.UnsupportedFormat]] rather than a silent guess (§4).
 *
 * JSON is read leniently through Typesafe Config (HOCON is a superset of JSON), so [[Json]] and [[Hocon]]
 * share a provider; they stay distinct here only so a `.json` file is named as JSON when it cannot be parsed.
 */
private[config] enum ConfigFormat:
  case Yaml, Json, Hocon

private[config] object ConfigFormat:
  def of(path: os.Path): Either[ConfigError, ConfigFormat] =
    dottedExtension(path.last).toLowerCase(Locale.ROOT) match
      case ".json"            => Right(Json)
      case ".yaml" | ".yml"   => Right(Yaml)
      case ".conf" | ".hocon" => Right(Hocon)
      case extension          => Left(ConfigError.UnsupportedFormat(path, extension))

  // The extension is taken from the last dot onward, keeping the dot and treating a leading dot as an
  // extension (`.json` is JSON), unlike `os.Path.ext`.
  private def dottedExtension(fileName: String): String = fileName.lastIndexOf('.') match
    case -1    => ""
    case index => fileName.substring(index)

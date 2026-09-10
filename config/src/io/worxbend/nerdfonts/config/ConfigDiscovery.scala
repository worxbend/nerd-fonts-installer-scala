package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.fonts.InstallConfig

/**
 * Walks the candidate list and loads the first file that exists.
 *
 * Only "does not exist" moves on to the next candidate (Go: `errors.Is(err, os.ErrNotExist)`). A candidate
 * that exists but fails to read, parse or validate is returned as the error, never skipped: silently falling
 * through to a later file, or to the picker, would hide the broken file the user most likely meant to use.
 */
object ConfigDiscovery:
  def discover(
      env: Environment,
      load: os.Path => Either[ConfigError, InstallConfig],
  ): Either[ConfigError, Option[DiscoveredConfig]] =
    ConfigLocations.candidates(env).flatMap(firstExisting(_, load))

  private def firstExisting(
      candidates: Vector[os.Path],
      load: os.Path => Either[ConfigError, InstallConfig],
  ): Either[ConfigError, Option[DiscoveredConfig]] = candidates.iterator
    .map(attempt(_, load))
    .collectFirst:
      case Left(error)        => Left(error)
      case Right(Some(found)) => Right(Some(found))
    .getOrElse(Right(None))

  private def attempt(
      path: os.Path,
      load: os.Path => Either[ConfigError, InstallConfig],
  ): Either[ConfigError, Option[DiscoveredConfig]] = load(path) match
    case Right(config)                 => Right(Some(DiscoveredConfig(path, config)))
    case Left(ConfigError.NotFound(_)) => Right(None)
    case Left(error)                   => Left(error)

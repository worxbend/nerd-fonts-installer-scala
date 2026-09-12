package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.Environment
import io.worxbend.nerdfonts.fonts.InstallConfig

import zio.IO
import zio.ZIO

/**
 * Walks the candidate list and loads the first file that exists.
 *
 * Only "does not exist" moves on to the next candidate. A candidate that exists but fails to read, parse or
 * validate is returned as the error, never skipped: silently falling through to a later file would hide the
 * broken file the user most likely meant to use.
 */
object ConfigDiscovery:
  def discover(
      env: Environment,
      load: os.Path => IO[ConfigError, InstallConfig],
  ): IO[ConfigError, Option[DiscoveredConfig]] =
    ConfigLocations.candidates(env).flatMap(firstExisting(_, load))

  private def firstExisting(
      candidates: Vector[os.Path],
      load: os.Path => IO[ConfigError, InstallConfig],
  ): IO[ConfigError, Option[DiscoveredConfig]] = candidates match
    case head +: tail => load(head).foldZIO(
        {
          case ConfigError.NotFound(_) => firstExisting(tail, load)
          case error                   => ZIO.fail(error)
        },
        config => ZIO.succeed(Some(DiscoveredConfig(head, config))),
      )
    case _            => ZIO.succeed(None)

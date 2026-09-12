package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.fonts.InstallConfig

import java.nio.file.NoSuchFileException

import zio.Chunk
import zio.Config
import zio.ConfigProvider
import zio.IO
import zio.ZIO
import zio.config.typesafe.TypesafeConfigProvider
import zio.config.yaml.YamlConfigProvider

/**
 * Turns a config file path into a validated `InstallConfig`: pick the format by extension, read the bytes,
 * hand them to the matching zio-config provider, apply the defaults and validate.
 *
 * This is the only place the steps meet, so the explicit `--config`, the environment variable and every
 * discovered candidate load by the same rules; discovery relies on `NotFound` staying distinguishable from
 * every other failure.
 */
object ConfigLoader:
  def load(path: os.Path): IO[ConfigError, InstallConfig] =
    for
      format   <- ZIO.fromEither(ConfigFormat.of(path))
      text     <- read(path)
      document <- decode(path, format, text)
      config   <- ZIO.fromEither(document.validated.left.map(ConfigError.Invalid(path, _)))
    yield config

  private def read(path: os.Path): IO[ConfigError, String] = ZIO
    .attemptBlockingIO(os.read(path))
    .mapError:
      case _: NoSuchFileException => ConfigError.NotFound(path)
      case error                  => ConfigError.Unreadable(path, Diagnostics.describe(error))

  // A malformed file fails one of two ways: the provider throws while parsing (YAML -> snakeyaml's
  // `ParserException`, HOCON/JSON -> Typesafe `ConfigException.Parse`), before `load` runs, so provider
  // construction is wrapped in `ZIO.attempt`; or a value will not fit the schema, which `load` reports as a
  // `Config.Error`. Both collapse to a single `Parse` line.
  private def decode(path: os.Path, format: ConfigFormat, text: String): IO[ConfigError, ConfigDocument] =
    provider(format, text)
      .mapError(throwable => ConfigError.Parse(path, firstLine(throwable.getMessage)))
      .flatMap(_.load(ConfigDto.config).mapError(error => ConfigError.Parse(path, renderError(error))))
      .map(_.toDocument)

  private def provider(format: ConfigFormat, text: String): IO[Throwable, ConfigProvider] =
    ZIO.attempt(format match
      case ConfigFormat.Yaml                      => YamlConfigProvider.fromYamlString(text)
      case ConfigFormat.Json | ConfigFormat.Hocon => TypesafeConfigProvider.fromHoconString(text))

  private def firstLine(message: String): String = Option(message)
    .map(_.linesIterator)
    .filter(_.hasNext)
    .map(_.next().trim)
    .filter(_.nonEmpty)
    .getOrElse("invalid configuration")

  // A raw `Config.Error.toString` embeds a ZIO fiber stack trace, so the ADT is folded by hand into one line,
  // dropping the ` at '...'` suffix when the path is empty.
  private def renderError(err: Config.Error): String =
    def at(path: Chunk[String], message: String): String =
      if path.isEmpty then message else s"$message at '${path.mkString(".")}'"
    def render(e: Config.Error): List[String]            = e match
      case Config.Error.MissingData(p, m)          => List(at(p, m))
      case Config.Error.InvalidData(p, m)          => List(at(p, m))
      case Config.Error.SourceUnavailable(p, m, _) => List(at(p, m))
      case Config.Error.Unsupported(p, m)          => List(at(p, m))
      case Config.Error.And(l, r)                  => render(l) ++ render(r)
      case Config.Error.Or(l, r)                   => render(l) ++ render(r)
    render(err).distinct.mkString("; ")

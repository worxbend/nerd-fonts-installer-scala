package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.GoQuote
import io.worxbend.nerdfonts.fonts.RefreshFontCache

import ox.either
import ox.either.ok

/**
 * The one strict reading of the four configuration keys, shared by the YAML and JSON decoders.
 *
 * Strictness is what keeps a typo such as `font_family` from silently installing nothing: unknown and duplicated
 * keys are errors, as in the Go loader (`DisallowUnknownFields` / `KnownFields(true)`), and a value of the
 * wrong shape is reported by field name rather than coerced.
 */
private[config] object ConfigFieldDecoder:
  private val releaseKey          = "release"
  private val destinationKey      = "destination"
  private val refreshFontCacheKey = "refresh_font_cache"
  private val familiesKey         = "families"
  private val knownKeys           = Set(releaseKey, destinationKey, refreshFontCacheKey, familiesKey)

  private val stringType  = "string"
  private val booleanType = "boolean"
  private val listType    = "list of strings"

  def decode(path: os.Path, root: ConfigNode): Either[ConfigError, ConfigDocument] = root match
    case ConfigNode.Null            => Right(ConfigDocument.empty)
    case ConfigNode.Mapping(fields) => decodeFields(path, fields)
    case _                          => Left(ConfigError.Parse(path, "document must be a mapping of configuration keys"))

  private def decodeFields(
      path: os.Path,
      fields: Vector[(String, ConfigNode)],
  ): Either[ConfigError, ConfigDocument] = either:
    rejectUnknown(path, fields).ok()
    rejectDuplicates(path, fields).ok()
    val byName = fields.toMap
    ConfigDocument(
      release = text(path, byName, releaseKey).ok(),
      destination = text(path, byName, destinationKey).ok(),
      refreshFontCache = flag(path, byName).ok(),
      families = families(path, byName).ok(),
    )

  private def rejectUnknown(path: os.Path, fields: Vector[(String, ConfigNode)]): Either[ConfigError, Unit] =
    fields
      .collectFirst { case (name, _) if !knownKeys(name) => name }
      .toLeft(())
      .left
      .map(ConfigError.UnknownField(path, _))

  // yaml.v3 rejects a repeated mapping key; JSON never produces one because ujson keeps the last value.
  private def rejectDuplicates(
      path: os.Path,
      fields: Vector[(String, ConfigNode)],
  ): Either[ConfigError, Unit] =
    val names = fields.map(_._1)
    names.zipWithIndex
      .collectFirst { case (name, index) if names.indexOf(name) < index => name }
      .toLeft(())
      .left
      .map(name => ConfigError.Parse(path, s"mapping key ${GoQuote.quote(name)} already defined"))

  private def text(
      path: os.Path,
      byName: Map[String, ConfigNode],
      key: String,
  ): Either[ConfigError, Option[String]] = byName.get(key) match
    case None | Some(ConfigNode.Null)            => Right(None)
    case Some(ConfigNode.Scalar(Some(value), _)) => Right(Some(value))
    case Some(_)                                 => Left(ConfigError.WrongType(path, key, stringType))

  private def flag(
      path: os.Path,
      byName: Map[String, ConfigNode],
  ): Either[ConfigError, Option[RefreshFontCache]] = byName.get(refreshFontCacheKey) match
    case None | Some(ConfigNode.Null)            => Right(None)
    case Some(ConfigNode.Scalar(_, Some(value))) => Right(Some(RefreshFontCache.fromBoolean(value)))
    case Some(_)                                 => Left(ConfigError.WrongType(path, refreshFontCacheKey, booleanType))

  private def families(
      path: os.Path,
      byName: Map[String, ConfigNode],
  ): Either[ConfigError, Option[Vector[String]]] = byName.get(familiesKey) match
    case None | Some(ConfigNode.Null)     => Right(None)
    case Some(ConfigNode.Sequence(items)) => entries(path, items).map(Some(_))
    case Some(_)                          => Left(ConfigError.WrongType(path, familiesKey, listType))

  private def entries(path: os.Path, items: Vector[ConfigNode]): Either[ConfigError, Vector[String]] =
    items.foldLeft[Either[ConfigError, Vector[String]]](Right(Vector.empty)): (accepted, item) =>
      accepted.flatMap(names => entry(path, item).map(names ++ _))

  // A null entry is dropped, as yaml.v3 does for a `[]string`; the JSON adapter has already turned its nulls
  // into the empty string that encoding/json would produce, so it never reaches this case.
  private def entry(path: os.Path, item: ConfigNode): Either[ConfigError, Option[String]] = item match
    case ConfigNode.Null                  => Right(None)
    case ConfigNode.Scalar(Some(text), _) => Right(Some(text))
    case _                                => Left(ConfigError.WrongType(path, familiesKey, listType))

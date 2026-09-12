package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.RefreshFontCache

import zio.Config
import zio.config.derivation.name
import zio.config.magnolia.deriveConfig

/**
 * The verbatim shape of a config file, as zio-config reads it: every key optional, every leaf a raw type.
 *
 * zio-config's magnolia derivation maps Scala field names to keys with no transform, so the snake_case key
 * `refresh_font_cache` is bound explicitly with `@name`; the other three keys are single words that already
 * match a Scala identifier. A global `.snakeCase` transform is deliberately avoided — it would rewrite the
 * three single-word keys too. The DTO is mapped to [[ConfigDocument]] so the Go `ApplyDefaults` + `Normalize`
 * + `Validate` sequence lives in exactly one place, independent of the file format that produced it.
 */
final private[config] case class ConfigDto(
    release: Option[String],
    destination: Option[String],
    @name("refresh_font_cache") refreshFontCache: Option[Boolean],
    families: Option[Vector[String]],
):
  def toDocument: ConfigDocument = ConfigDocument(
    release = release,
    destination = destination,
    refreshFontCache = refreshFontCache.map(RefreshFontCache.fromBoolean),
    families = families,
  )

private[config] object ConfigDto:
  /** The magnolia-derived reader; one schema serves all three providers. */
  val config: Config[ConfigDto] = deriveConfig[ConfigDto]

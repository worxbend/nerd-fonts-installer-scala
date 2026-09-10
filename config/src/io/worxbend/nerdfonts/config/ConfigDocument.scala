package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.InstallConfig
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.fonts.ReleaseSelector

/**
 * What a config file said, before defaults: every key optional, text untrimmed.
 *
 * Kept apart from `InstallConfig` so the decoders answer only "which keys were present and what did they hold",
 * and the Go `ApplyDefaults` + `Normalize` + `Validate` sequence exists exactly once, in [[validated]],
 * regardless of the file format that produced the document.
 */
final case class ConfigDocument(
    release: Option[String] = None,
    destination: Option[String] = None,
    refreshFontCache: Option[RefreshFontCache] = None,
    families: Option[Vector[String]] = None,
):
  /** Applies the Go defaults and hands the raw text to the shared validation in `core`. */
  def validated: Either[ConfigValidationError, InstallConfig] = InstallConfig.validated(
    release = ConfigDocument.orDefault(release, ReleaseSelector.latestKeyword),
    destination = ConfigDocument.orDefault(destination, DestinationPath.default.value),
    refreshFontCache = refreshFontCache.getOrElse(RefreshFontCache.Disabled),
    families = families.getOrElse(Vector.empty),
  )

object ConfigDocument:
  /** The document every key is missing from; what an empty file and a `null` document decode to. */
  val empty: ConfigDocument = ConfigDocument()

  // Go's ApplyDefaults tests `== ""` before Normalize trims, so an explicit empty string takes the default while a
  // whitespace-only value reaches Validate and is rejected there.
  private def orDefault(value: Option[String], default: String): String =
    value.filter(_.nonEmpty).getOrElse(default)

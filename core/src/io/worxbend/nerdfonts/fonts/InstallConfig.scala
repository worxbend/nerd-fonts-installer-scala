package io.worxbend.nerdfonts.fonts

/**
 * A validated install configuration: what to install, where, and whether to refresh the font cache.
 *
 * Every field is already a domain type, so holding an `InstallConfig` proves that the §4 validation rules
 * held at construction time. Raw input enters only through [[InstallConfig.validated]].
 */
final case class InstallConfig(
    selector: ReleaseSelector,
    destination: DestinationPath,
    refreshFontCache: RefreshFontCache,
    families: Vector[FamilyName],
)

object InstallConfig:
  /**
   * Applies the Go `Normalize` + `Validate` rules to raw (already defaulted) input, in the Go order: release,
   * destination, empty family list, then each family in turn (unsafe before duplicate). The first failure
   * wins, exactly as the reference reports one error at a time.
   */
  def validated(
      release: String,
      destination: String,
      refreshFontCache: RefreshFontCache,
      families: Vector[String],
  ): Either[ConfigValidationError, InstallConfig] =
    for
      tag   <- ReleaseTag.parse(release).toRight(ConfigValidationError.ReleaseRequired)
      root  <- DestinationPath.parse(destination).toRight(ConfigValidationError.DestinationRequired)
      names <- validatedFamilies(families)
    yield InstallConfig(ReleaseSelector.of(tag), root, refreshFontCache, names)

  private def validatedFamilies(raw: Vector[String]): Either[ConfigValidationError, Vector[FamilyName]] =
    if raw.isEmpty then Left(ConfigValidationError.NoFamilies)
    else
      raw
        .map(_.trim)
        .foldLeft[Either[ConfigValidationError, Vector[FamilyName]]](Right(Vector.empty)):
          (accepted, candidate) => accepted.flatMap(appendFamily(_, candidate))

  private def appendFamily(
      accepted: Vector[FamilyName],
      candidate: String,
  ): Either[ConfigValidationError, Vector[FamilyName]] = FamilyName
    .parse(candidate)
    .left
    .map(ConfigValidationError.InvalidFamily(_))
    .flatMap: name =>
      if accepted.contains(name) then Left(ConfigValidationError.DuplicateFamily(name))
      else Right(accepted :+ name)

/** Why raw configuration input was rejected; renders the Go messages verbatim. */
enum ConfigValidationError:
  case ReleaseRequired
  case DestinationRequired
  case NoFamilies
  case InvalidFamily(cause: FamilyNameError)
  case DuplicateFamily(name: FamilyName)

  def render: String = this match
    case ReleaseRequired       => "release is required"
    case DestinationRequired   => "destination is required"
    case NoFamilies            => "at least one font family is required"
    case InvalidFamily(cause)  => cause.render
    case DuplicateFamily(name) => s"duplicate font family ${GoQuote.quote(name.value)}"

package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.config.ConfigError
import io.worxbend.nerdfonts.config.ConfigLocations
import io.worxbend.nerdfonts.environment.PathError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.install.InstallError
import io.worxbend.nerdfonts.picker.PickerError
import io.worxbend.nerdfonts.releases.ReleaseError

/**
 * Why a run did not reach a successful outcome. Every case renders exactly one stderr line, and this is the
 * only place the Go operation prefixes (`load config <path>: `, `load discovered config <path>: `,
 * `install fonts: `) are added: the nested error ADTs render their detail only, so the same `ConfigError`
 * reads differently depending on how the file was chosen, exactly as the reference wraps it.
 */
enum AppFailure:
  /** An explicit `--config` or `$NERD_FONTS_INSTALLER_CONFIG` file failed; `path` is the text as given. */
  case Config(cause: ConfigError, path: String)
  case DiscoveredConfig(cause: ConfigError)

  /** No config anywhere and no picker; `candidates` is what discovery searched, possibly empty. */
  case NoConfig(candidates: Vector[os.Path])
  case NotATerminal
  case Release(cause: ReleaseError)
  case Picker(cause: PickerError)
  case UnsafeSelection(cause: ConfigValidationError)
  case Destination(cause: PathError)
  case Install(cause: InstallError)
  case Interrupted(phase: InterruptPhase)

  def render: String = this match
    case Config(cause, path)                       => s"load config $path: ${AppFailure.renderAsTyped(cause, path)}"
    case DiscoveredConfig(cause)                   => AppFailure.renderDiscovered(cause)
    case NoConfig(candidates)                      => AppFailure.renderNoConfig(candidates)
    case NotATerminal                              => s"${AppFailure.noConfigFound}; --interactive requires stdin and stdout terminals"
    case Release(cause)                            => cause.render
    case Picker(cause)                             => cause.render
    case UnsafeSelection(cause)                    => s"${AppFailure.installPrefix}${cause.render}"
    case Destination(cause)                        => s"${AppFailure.installPrefix}${cause.render}"
    case Install(cause)                            => s"${AppFailure.installPrefix}${cause.render}"
    case Interrupted(InterruptPhase.Install)       => s"${AppFailure.installPrefix}interrupted"
    case Interrupted(InterruptPhase.BeforeInstall) => "interrupted"

object AppFailure:
  /** Go's `errNoConfig` text; the two no-config messages and their exit code hang off it. */
  val noConfigFound: String = "no config found"

  /** Everything that fails inside the install step is reported the way Go's `install fonts: %v` wraps it. */
  val installPrefix: String = "install fonts: "

  private def renderNoConfig(candidates: Vector[os.Path]): String =
    val variable = ConfigLocations.configVariable
    if candidates.isEmpty then s"$noConfigFound; pass --config or set $variable"
    else s"$noConfigFound; pass --config, set $variable, or create one of: ${candidates.mkString(", ")}"

  // A missing working directory has no candidate path to name, and Go returns that error unwrapped.
  private def renderDiscovered(cause: ConfigError): String = configPath(cause) match
    case Some(path) => s"load discovered config $path: ${cause.render}"
    case None       => cause.render

  // `ConfigError.render` always spells the path as the absolute `os.Path` the loader had to read the file
  // with; an explicit `--config`/`$NERD_FONTS_INSTALLER_CONFIG` value is echoed as typed everywhere else in
  // the message, so the same absolute spelling is substituted back to `raw` here (Go never absolutises the
  // path at all, so both the outer wrap and the inner error share the one raw string). A discovered candidate
  // has no separate raw spelling — it is already the path `renderDiscovered` prints — so this is a no-op there.
  private def renderAsTyped(cause: ConfigError, raw: String): String = configPath(cause) match
    case Some(path) => cause.render.replace(path.toString, raw)
    case None       => cause.render

  private def configPath(cause: ConfigError): Option[os.Path] = cause match
    case ConfigError.NotFound(path)        => Some(path)
    case ConfigError.Unreadable(path, _)   => Some(path)
    case ConfigError.Parse(path, _)        => Some(path)
    case ConfigError.UnknownField(path, _) => Some(path)
    case ConfigError.WrongType(path, _, _) => Some(path)
    case ConfigError.Invalid(path, _)      => Some(path)
    case ConfigError.NoWorkingDirectory(_) => None

/**
 * Where an interrupt landed. Go reports a SIGINT during the install as `install fonts: … context canceled`,
 * and anywhere else without the prefix; the exit code is 1 either way.
 */
enum InterruptPhase:
  case Install, BeforeInstall

package io.worxbend.nerdfonts.config

import io.worxbend.nerdfonts.environment.EnvironmentError
import io.worxbend.nerdfonts.fonts.ConfigValidationError
import io.worxbend.nerdfonts.fonts.GoQuote

/**
 * Why a configuration file could not become an `InstallConfig`.
 *
 * `render` yields the detail only: the CLI prefixes it with `load config <path>: ` or
 * `load discovered config <path>: ` depending on how the path was chosen, so one error type serves both routes.
 * `NotFound` is kept apart from `Unreadable` because discovery skips only the former (Go:
 * `errors.Is(err, os.ErrNotExist)`); a candidate that exists but cannot be read or parsed is fatal, since it is
 * almost certainly the file the user meant.
 */
enum ConfigError:
  case NotFound(path: os.Path)
  case Unreadable(path: os.Path, cause: String)
  case Parse(path: os.Path, message: String)
  case UnknownField(path: os.Path, field: String)
  case WrongType(path: os.Path, field: String, expected: String)
  case Invalid(path: os.Path, cause: ConfigValidationError)
  case NoWorkingDirectory(cause: EnvironmentError)

  def render: String = this match
    case NotFound(path)                   => s"open $path: no such file or directory"
    case Unreadable(path, cause)          => s"read $path: $cause"
    case Parse(path, message)             => s"parse $path: $message"
    case UnknownField(path, field)        => s"parse $path: unknown field ${GoQuote.quote(field)}"
    case WrongType(path, field, expected) => s"parse $path: field ${GoQuote.quote(field)} must be a $expected"
    case Invalid(_, cause)                => cause.render
    case NoWorkingDirectory(cause)        => s"locate current directory: ${cause.render}"

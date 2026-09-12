package io.worxbend.nerdfonts.environment

import io.worxbend.nerdfonts.Diagnostics
import io.worxbend.nerdfonts.fonts.DestinationPath

import java.nio.file.Path

import scala.util.Try

import zio.IO
import zio.ZIO

/**
 * Turns the configured destination into an absolute path with exactly the Go `expandPath` rules.
 *
 * Only a bare `~` and a leading `~/` are expanded. `~user`, an embedded `~` and everything else are taken
 * literally, because that is what the reference does and because guessing at other users' homes would make
 * the same config file mean different things on different machines. Relative paths resolve against the
 * working directory, which is what the operating system would do with them anyway.
 */
object PathExpander:
  private val tildePrefix = "~/"

  def expand(path: DestinationPath, env: Environment): IO[PathError, os.Path] =
    val raw = path.value
    if raw == "~" then home(env)
    else if raw.startsWith(tildePrefix) then
      home(env).flatMap(h => ZIO.fromEither(joined(h, raw.drop(tildePrefix.length), raw)))
    else if raw.startsWith("/") then ZIO.fromEither(absolute(raw))
    else
      env.workingDirectory
        .mapError(PathError.NoWorkingDirectory(_))
        .flatMap(cwd => ZIO.fromEither(resolved(raw, cwd)))

  private def home(env: Environment): IO[PathError, os.Path] = env.homeDirectory.someOrFail(PathError.NoHome)

  // `Path.of(home, rest)` joins and normalises like Go's `filepath.Join`, including `..` segments and doubled
  // separators, so `~//x` and `~/a/../b` land where Go puts them.
  private def joined(home: os.Path, rest: String, raw: String): Either[PathError, os.Path] =
    Try(os.Path(Path.of(home.toString, rest))).toEither.left.map(invalid(raw, _))

  private def absolute(raw: String): Either[PathError, os.Path] =
    Try(os.Path(raw)).toEither.left.map(invalid(raw, _))

  private def resolved(raw: String, cwd: os.Path): Either[PathError, os.Path] =
    Try(os.Path(raw, cwd)).toEither.left.map(invalid(raw, _))

  private def invalid(raw: String, error: Throwable): PathError =
    PathError.Invalid(raw, Diagnostics.describe(error))

/** Why a destination could not be turned into an absolute path. */
enum PathError:
  /** Go's `os.UserHomeDir` failure text on Unix. */
  case NoHome
  case NoWorkingDirectory(cause: EnvironmentError)
  case Invalid(path: String, cause: String)

  def render: String = this match
    case NoHome                    => "$HOME is not defined"
    case NoWorkingDirectory(cause) => s"locate current directory: ${cause.render}"
    case Invalid(path, cause)      => s"invalid destination $path: $cause"

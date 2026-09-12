package io.worxbend.nerdfonts.environment

import io.worxbend.nerdfonts.Diagnostics

import scala.util.Try

import zio.IO
import zio.UIO
import zio.ZIO

/**
 * The ambient process environment: variables plus the two directories that path expansion and config
 * discovery depend on.
 *
 * Nothing below the composition root reads `sys.env` or `sys.props` directly. Those are global mutable
 * state: code that reads them cannot be tested without mutating the whole JVM, and two such tests cannot run
 * in parallel. Everything that needs the environment receives one of these instead.
 *
 * Every read is a `ZIO` effect, not because a variable lookup itself is slow, but because it is a read of
 * mutable process-wide state (an effect in the same sense a clock read is), and because `workingDirectory`'s
 * `os.pwd` is a genuine blocking syscall that must run on the blocking pool.
 */
trait Environment:
  /** The value of an environment variable, or `None` when it is not set. */
  def variable(name: String): UIO[Option[String]]

  /** The value of a JVM system property, or `None` when it is not set (e.g. the `java.io.tmpdir` fallback). */
  def property(name: String): UIO[Option[String]]

  /** The current user's home directory, or `None` when the process cannot determine one. */
  def homeDirectory: UIO[Option[os.Path]]

  /** The directory the process was started in; a failure means the working directory cannot be determined. */
  def workingDirectory: IO[EnvironmentError, os.Path]

object Environment:
  /**
   * The real environment of the running process; the only place in the codebase that reads `sys.env` or
   * `sys.props`. `homeDirectory` reads only `$HOME` on Unix, deliberately with no fallback to the JVM's
   * passwd-derived `user.home`, so a process started with `$HOME` unset or blank has no home here either,
   * and `PathExpander`'s `PathError.NoHome` (`$HOME is not defined`) can fire in production instead of being
   * masked by a home derived from the passwd database.
   */
  object System extends Environment:
    def variable(name: String): UIO[Option[String]] = ZIO.succeed(sys.env.get(name))

    def property(name: String): UIO[Option[String]] = ZIO.succeed(sys.props.get(name))

    def homeDirectory: UIO[Option[os.Path]] = variable("HOME").map(homeFrom)

    def workingDirectory: IO[EnvironmentError, os.Path] = ZIO
      .attemptBlockingIO(os.pwd)
      .mapError(error => EnvironmentError.NoWorkingDirectory(Diagnostics.describe(error)))

  // A pure projection of the `$HOME` rule, kept apart from `sys.env` so a test can drive both cases (set,
  // blank or unset) without mutating global process state.
  private[environment] def homeFrom(home: Option[String]): Option[os.Path] =
    home.filter(_.nonEmpty).flatMap(raw => Try(os.Path(raw)).toOption)

  /** A fixed environment, for tests and for any caller that needs expansion to be reproducible. */
  def fixed(
      variables: Map[String, String] = Map.empty,
      homeDirectory: Option[os.Path] = Some(os.Path("/home/test")),
      workingDirectory: Either[EnvironmentError, os.Path] = Right(os.Path("/workspace")),
      properties: Map[String, String] = Map.empty,
  ): Environment =
    val home    = homeDirectory
    val working = workingDirectory
    new Environment:
      def variable(name: String): UIO[Option[String]]     = ZIO.succeed(variables.get(name))
      def property(name: String): UIO[Option[String]]     = ZIO.succeed(properties.get(name))
      def homeDirectory: UIO[Option[os.Path]]             = ZIO.succeed(home)
      def workingDirectory: IO[EnvironmentError, os.Path] = ZIO.fromEither(working)

/** Why the environment could not answer; the text is the platform's own wording, not a parity target. */
enum EnvironmentError:
  case NoWorkingDirectory(cause: String)

  def render: String = this match
    case NoWorkingDirectory(cause) => cause

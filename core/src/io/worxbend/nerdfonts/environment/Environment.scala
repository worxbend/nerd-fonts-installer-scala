package io.worxbend.nerdfonts.environment

import io.worxbend.nerdfonts.Diagnostics

import scala.util.Try

/**
 * The ambient process environment: variables plus the two directories that path expansion and config
 * discovery depend on.
 *
 * Nothing below the composition root reads `sys.env` or `sys.props` directly. Those are global mutable
 * state: code that reads them cannot be tested without mutating the whole JVM, and two such tests cannot run
 * in parallel. Everything that needs the environment receives one of these instead.
 */
trait Environment:
  /** The value of an environment variable, or `None` when it is not set. */
  def variable(name: String): Option[String]

  /** The value of a JVM system property, or `None` when it is not set (e.g. the `java.io.tmpdir` fallback). */
  def property(name: String): Option[String]

  /** The current user's home directory, or `None` when the process cannot determine one (Go: `os.UserHomeDir` error). */
  def homeDirectory: Option[os.Path]

  /** The directory the process was started in; a `Left` mirrors Go's `os.Getwd` failing. */
  def workingDirectory: Either[EnvironmentError, os.Path]

object Environment:
  /**
   * The real environment of the running process; the only place in the codebase that reads `sys.env` or
   * `sys.props`. `homeDirectory` reads only `$HOME`, exactly as Go's `os.UserHomeDir` does on Unix: no
   * fallback to the JVM's passwd-derived `user.home`, so a process started with `$HOME` unset or blank has no
   * home here either, and `PathExpander`'s `PathError.NoHome` (`$HOME is not defined`) can fire in production
   * instead of being masked by a home the reference would never have found.
   */
  object System extends Environment:
    def variable(name: String): Option[String] = sys.env.get(name)

    def property(name: String): Option[String] = sys.props.get(name)

    def homeDirectory: Option[os.Path] = homeFrom(variable("HOME"))

    def workingDirectory: Either[EnvironmentError, os.Path] =
      Try(os.pwd).toEither.left.map(error => EnvironmentError.NoWorkingDirectory(Diagnostics.describe(error)))

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
      def variable(name: String): Option[String]              = variables.get(name)
      def property(name: String): Option[String]              = properties.get(name)
      def homeDirectory: Option[os.Path]                      = home
      def workingDirectory: Either[EnvironmentError, os.Path] = working

/** Why the environment could not answer; the text is the platform's own wording, not a parity target. */
enum EnvironmentError:
  case NoWorkingDirectory(cause: String)

  def render: String = this match
    case NoWorkingDirectory(cause) => cause

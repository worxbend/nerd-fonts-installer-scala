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

  /** The current user's home directory, or `None` when the process cannot determine one (Go: `os.UserHomeDir` error). */
  def homeDirectory: Option[os.Path]

  /** The directory the process was started in; a `Left` mirrors Go's `os.Getwd` failing. */
  def workingDirectory: Either[EnvironmentError, os.Path]

object Environment:
  /**
   * The real environment of the running process; the only place in the codebase that reads `sys.env` or
   * `sys.props`. `$HOME` is consulted first to match Go's `os.UserHomeDir`; `user.home` is the JVM fallback
   * for a process started without `$HOME`.
   */
  object System extends Environment:
    def variable(name: String): Option[String] = sys.env.get(name)

    def homeDirectory: Option[os.Path] =
      variable("HOME").filter(_.nonEmpty).orElse(sys.props.get("user.home")).flatMap(absolutePath)

    def workingDirectory: Either[EnvironmentError, os.Path] =
      Try(os.pwd).toEither.left.map(error => EnvironmentError.NoWorkingDirectory(Diagnostics.describe(error)))

    private def absolutePath(raw: String): Option[os.Path] = Try(os.Path(raw)).toOption

  /** A fixed environment, for tests and for any caller that needs expansion to be reproducible. */
  def fixed(
      variables: Map[String, String] = Map.empty,
      homeDirectory: Option[os.Path] = Some(os.Path("/home/test")),
      workingDirectory: Either[EnvironmentError, os.Path] = Right(os.Path("/workspace")),
  ): Environment =
    val home    = homeDirectory
    val working = workingDirectory
    new Environment:
      def variable(name: String): Option[String]              = variables.get(name)
      def homeDirectory: Option[os.Path]                      = home
      def workingDirectory: Either[EnvironmentError, os.Path] = working

/** Why the environment could not answer; the text is the platform's own wording, not a parity target. */
enum EnvironmentError:
  case NoWorkingDirectory(cause: String)

  def render: String = this match
    case NoWorkingDirectory(cause) => cause

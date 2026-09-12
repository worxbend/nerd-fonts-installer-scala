package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.releases.ReleaseError

import picocli.CommandLine

/**
 * The one place an application result becomes a POSIX exit status.
 *
 * Mirrors Go's `exitCodeFor`: only the user-correctable failures (no config, an unknown release tag, no releases
 * at all) are 2; everything else that went wrong, including a config file that exists but cannot be loaded, is
 * 1. picocli produces its own 2 for malformed flags and 0 for `--help`/`--version` inside
 * `CommandLine.execute`; those are the only codes not routed through here, and the constants below are
 * picocli's so the two sources can never disagree.
 */
object ExitCode:
  val success: Int = CommandLine.ExitCode.OK
  val failure: Int = CommandLine.ExitCode.SOFTWARE
  val usage: Int   = CommandLine.ExitCode.USAGE

  def of(result: Either[AppFailure, AppOutcome]): Int = result match
    case Right(_)                                                                     => success
    case Left(AppFailure.NoConfig(_))                                                 => usage
    case Left(AppFailure.Release(ReleaseError.NotFound(_) | ReleaseError.NoReleases)) => usage
    case Left(_)                                                                      => failure

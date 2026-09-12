package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.releases.ReleaseError

/**
 * The one place an application result becomes a POSIX exit status.
 *
 * The classification rule: only the user-correctable failures (no config, an unknown release tag, no
 * releases at all) are 2; everything else that went wrong, including a config file that exists but cannot be
 * loaded, is 1. A malformed or unknown flag is a `2` produced by the hand-rolled parser (SPEC §7), and
 * `--help`/`--version` are a `0` produced there too; those are the only codes not routed through here.
 */
object ExitCode:
  val success: Int = 0
  val failure: Int = 1
  val usage: Int   = 2

  def of(result: Either[AppFailure, AppOutcome]): Int = result match
    case Right(_)                                                                     => success
    case Left(AppFailure.NoConfig(_))                                                 => usage
    case Left(AppFailure.Release(ReleaseError.NotFound(_) | ReleaseError.NoReleases)) => usage
    case Left(_)                                                                      => failure

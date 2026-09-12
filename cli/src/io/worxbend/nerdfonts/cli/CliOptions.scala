package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.fonts.DryRun

/**
 * What the command line asked for after parsing.
 *
 * Immutable and free of any parser types so `Application` never sees the argument array. The explicit
 * `--config` value stays a raw `String`: the Go reference treats even `--config ""` as an explicit choice and
 * echoes the text exactly as typed in `load config <path>: …`, so the value must not be normalised before that
 * message.
 */
final case class CliOptions(
    explicitConfig: Option[String],
    mode: CliMode,
    dryRun: DryRun,
)

/** Which of the tool's two jobs was requested; `--font-names` never needs a full config or a destination. */
enum CliMode:
  case FontNames, Install

package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.fonts.DryRun
import io.worxbend.nerdfonts.picker.IconMode

/**
 * What the command line asked for, after picocli parsing and `--icons` validation.
 *
 * Immutable and free of picocli types so `Application` never sees the argument array. The explicit `--config`
 * value stays a raw `String`: the Go reference treats even `--config ""` as an explicit choice and echoes the
 * text exactly as typed in `load config <path>: …`, so the value must not be normalised before that message.
 */
final case class CliOptions(
    explicitConfig: Option[String],
    mode: CliMode,
    dryRun: DryRun,
    interactive: Interactive,
    icons: IconMode,
)

/** Which of the tool's two jobs was requested; `--font-names` never needs a full config or a destination. */
enum CliMode:
  case FontNames, Install

/** Whether `--interactive` was given; an enum so the picker gate cannot be handed a bare flag. */
enum Interactive:
  case Requested, NotRequested

object Interactive:
  /** The boundary conversion for the picocli flag binding. */
  def fromBoolean(value: Boolean): Interactive = if value then Requested else NotRequested

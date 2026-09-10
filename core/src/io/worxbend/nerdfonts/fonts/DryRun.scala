package io.worxbend.nerdfonts.fonts

/** Whether the installer only prints its plan; a two-case enum so call sites cannot pass a bare flag. */
enum DryRun:
  case Enabled, Disabled

object DryRun:
  /** The boundary conversion for the CLI flag binding. */
  def fromBoolean(value: Boolean): DryRun = if value then Enabled else Disabled

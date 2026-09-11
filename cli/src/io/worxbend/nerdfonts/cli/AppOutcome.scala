package io.worxbend.nerdfonts.cli

/**
 * How a successful run ended. Cancelling the picker is a success (exit 0) in the Go reference, so it is an
 * outcome here rather than a failure; keeping the four cases distinct lets tests assert which path ran
 * without inspecting the streams.
 */
enum AppOutcome:
  case Installed, DryRunPrinted, FontNamesPrinted, PickerCancelled

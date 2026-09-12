package io.worxbend.nerdfonts.cli

/**
 * How a successful run ended; keeping the cases distinct lets tests assert which path ran without inspecting
 * the streams.
 */
enum AppOutcome:
  case Installed, DryRunPrinted, FontNamesPrinted

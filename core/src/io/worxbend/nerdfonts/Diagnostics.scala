package io.worxbend.nerdfonts

/**
 * Turns a caught exception into the `<cause>` text of an error message. Kept in one place so every adapter
 * describes platform failures the same way, and so a `null` or empty message never produces a blank cause.
 */
private[nerdfonts] object Diagnostics:
  def describe(error: Throwable): String =
    Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

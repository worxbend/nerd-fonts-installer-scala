package io.worxbend.nerdfonts.environment

/**
 * Whether renderers may emit ANSI colour. Detection (`NO_COLOR`, `TERM=dumb`, a console being attached) is a
 * CLI concern; every renderer consumes only this value so the decision is made exactly once per process.
 */
enum ColourMode:
  case Ansi, Plain

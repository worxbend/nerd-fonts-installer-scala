package io.worxbend.nerdfonts.cli

import io.worxbend.nerdfonts.fonts.DryRun

import scala.annotation.tailrec

/**
 * The hand-rolled command-line parser (SPEC §7), deliberately not a framework: no reflection (so native-image
 * needs no configuration) and no silent tolerance of a mistyped flag.
 *
 * Long flags use the **double-dash** form only. A single-dash long flag such as `-config` or `-dry-run` is a
 * usage error rather than being accepted or ignored: silently dropping an unrecognised `-dry-run` would
 * perform a **real installation** instead of the intended dry run, so the parser fails loudly instead. Help
 * is the one exception — `-h` and `-help` are accepted alongside `--help`, because a misspelled help flag can
 * never trigger a destructive action.
 *
 * The argument vector is folded into an immutable [[CliOptions]] with no mutable state: repeated flags are
 * last-wins, and the first positional argument stops option parsing and is ignored (the remaining arguments
 * are discarded).
 */
private[cli] enum ParseResult:
  case Options(options: CliOptions)
  case ShowHelp
  case ShowVersion
  case Usage(message: String)

private[cli] object ArgumentParser:
  def parse(args: List[String]): ParseResult = fold(args, CliOptions(None, CliMode.Install, DryRun.Disabled))

  @tailrec
  private def fold(args: List[String], options: CliOptions): ParseResult = args match
    case Nil           => ParseResult.Options(options)
    // `--` terminates option parsing: everything after it is positional and ignored.
    case "--" :: _     => ParseResult.Options(options)
    case token :: rest => step(token, rest, options) match
        case Step.Done(result)               => result
        case Step.Continue(remaining, state) => fold(remaining, state)

  private def step(token: String, rest: List[String], options: CliOptions): Step = token match
    case "-h" | "-help" | "--help" => Step.Done(ParseResult.ShowHelp)
    case "--version"               => Step.Done(ParseResult.ShowVersion)
    case _ if isPositional(token)  => Step.Done(ParseResult.Options(options))
    case _                         => flag(token, rest, options)

  private def flag(token: String, rest: List[String], options: CliOptions): Step =
    val (name, inline) = split(token)
    name match
      case "--config"     => inline match
          case Some(value) => Step.Continue(rest, options.copy(explicitConfig = Some(value)))
          case None        => rest match
              // An option's value is taken verbatim, even one that looks like a flag; only a missing
              // trailing value is an error.
              case value :: tail => Step.Continue(tail, options.copy(explicitConfig = Some(value)))
              case Nil           => Step.Done(ParseResult.Usage(needsArgument(name)))
      case "--dry-run"    =>
        boolean(name, inline, rest, enabled => options.copy(dryRun = DryRun.fromBoolean(enabled)))
      case "--font-names" => boolean(
          name,
          inline,
          rest,
          enabled => options.copy(mode = if enabled then CliMode.FontNames else CliMode.Install),
        )
      case _              => Step.Done(ParseResult.Usage(notDefined(name)))

  // A boolean flag never consumes the next argument (`--flag` or `--flag=value`, never `--flag value`); an
  // inline value must parse, a bare flag means true.
  private def boolean(
      name: String,
      inline: Option[String],
      rest: List[String],
      update: Boolean => CliOptions,
  ): Step = inline match
    case None      => Step.Continue(rest, update(true))
    case Some(raw) => parseBoolean(raw) match
        case Some(value) => Step.Continue(rest, update(value))
        case None        => Step.Done(ParseResult.Usage(invalidBoolean(name, raw)))

  private def split(token: String): (String, Option[String]) = token.indexOf('=') match
    case -1    => (token, None)
    case index => (token.substring(0, index), Some(token.substring(index + 1)))

  // A token that does not start with `-`, or the bare `-`, is a positional argument.
  private def isPositional(token: String): Boolean = !token.startsWith("-") || token == "-"

  // The accepted set of boolean flag values.
  private def parseBoolean(raw: String): Option[Boolean] = raw match
    case "1" | "t" | "T" | "TRUE" | "true" | "True"    => Some(true)
    case "0" | "f" | "F" | "FALSE" | "false" | "False" => Some(false)
    case _                                             => None

  private def notDefined(flag: String): String    = s"flag provided but not defined: $flag"
  private def needsArgument(name: String): String = s"flag needs an argument: $name"

  private def invalidBoolean(name: String, raw: String): String = s"invalid boolean value \"$raw\" for $name"

/** One fold step: either a terminal parse result, or the next argument list and accumulated options. */
private enum Step:
  case Done(result: ParseResult)
  case Continue(rest: List[String], options: CliOptions)

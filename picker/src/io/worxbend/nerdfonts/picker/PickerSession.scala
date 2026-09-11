package io.worxbend.nerdfonts.picker

import io.worxbend.nerdfonts.environment.ColourMode
import io.worxbend.nerdfonts.fonts.DestinationPath
import io.worxbend.nerdfonts.fonts.RefreshFontCache
import io.worxbend.nerdfonts.releases.Release

import scala.annotation.tailrec

/** Why a picker session could not run at all; cancellation and rejection are outcomes, not errors. */
enum PickerError:
  case NoReleases
  case Terminal(cause: TerminalError)

  def render: String = this match
    case NoReleases      => "no Nerd Fonts releases available"
    case Terminal(cause) => cause.render

/**
 * The render → read → update loop: the Bubble Tea runtime reduced to a tail-recursive function. The model
 * is pure and the terminal is a port, so this is the only place the two meet, and it holds no state of its
 * own. Raw mode and the alternate screen are the loan's responsibility, so an interrupt unwinding through
 * `readKey` still restores the terminal.
 */
object PickerSession:
  def run(
      releases: Vector[Release],
      icons: IconMode,
      colours: ColourMode,
      terminal: Terminal,
  ): Either[PickerError, PickerOutcome] =
    if releases.isEmpty then Left(PickerError.NoReleases)
    else
      terminal
        .withRawMode: raw =>
          val model = PickerModel.initial(
            releases,
            DestinationPath.default,
            RefreshFontCache.Enabled,
            icons,
            raw.size(),
          )
          drive(raw, model, colours)
        .left
        .map(PickerError.Terminal(_))

  @tailrec
  private def drive(raw: RawTerminal, model: PickerModel, colours: ColourMode): PickerOutcome =
    val current = model.resized(raw.size())
    raw.write(PickerView.render(current, colours))
    raw.readKey() match
      case None      => PickerOutcome.Cancelled
      case Some(key) =>
        val next = current.update(key)
        next.outcome match
          case Some(outcome) => outcome
          case None          => drive(raw, next, colours)

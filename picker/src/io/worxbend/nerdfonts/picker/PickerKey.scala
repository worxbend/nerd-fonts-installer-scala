package io.worxbend.nerdfonts.picker

/**
 * A decoded key press, the only input the picker model ever sees.
 *
 * Raw terminal bytes are turned into these by `KeyDecoder`, and the test-side `ScriptedTerminal` feeds them
 * directly, so every rule of the key precedence table is exercised without a tty. `Char` carries a single
 * BMP character: font family names and filter text never need more, and a supplementary code point would
 * not fit the filter's per-character editing model.
 */
enum PickerKey:
  case Up, Down, Left, Right, PageUp, PageDown, Home, End, Tab, ShiftTab, Enter, Escape, Space, Backspace,
    CtrlC, CtrlJ, CtrlK
  case Char(value: scala.Char)

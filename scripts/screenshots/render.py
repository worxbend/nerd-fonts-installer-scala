#!/usr/bin/env python3
"""Render a captured terminal transcript into a self-contained SVG "terminal window".

Input is the raw bytes a program wrote to a terminal: text with ANSI SGR colour sequences, plus whatever
cursor and erase sequences a full-screen program uses to repaint itself. The transcript is replayed onto a
small cell grid (carriage return, line feed, tab, `ESC[H`, `ESC[K`, `ESC[J` and the SGR attributes are
honoured, everything else is skipped), so a full-screen program that redraws the same rows sixty times renders as its last frame, while
plain line-oriented output renders as exactly the lines it printed.

The SVG is sized from the content: one cell per column of the widest row, one line box per row. Colours
come from the SGR codes themselves (16-colour, 256-colour and truecolour), so a run painted `38;2;255;95;175`
is filled `#FF5FAF`. Box-drawing and block characters are drawn as vectors instead of text because a
monospace font's box glyphs only fill their own em box and leave visible gaps between lines.

Standard library only; no third-party packages.

Usage: render.py TRANSCRIPT OUTPUT.svg [--title TEXT] [--min-cols N]
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, replace
from xml.sax.saxutils import escape

# --- geometry -------------------------------------------------------------------------------------------

CELL_WIDTH = 8.4
LINE_HEIGHT = 18.0
FONT_SIZE = 14.0
BASELINE = 13.6  # baseline offset within a line box
PAD_X = 18.0
TITLE_BAR = 30.0
PAD_TOP = 44.0
PAD_BOTTOM = 16.0
RADIUS = 12.0
FONT_FAMILY = (
    "ui-monospace, SFMono-Regular, Menlo, Consolas, &quot;DejaVu Sans Mono&quot;, "
    "&quot;Liberation Mono&quot;, monospace"
)

# --- colours --------------------------------------------------------------------------------------------

BACKGROUND = "#13131F"
CHROME_TOP = "#221F35"
CHROME_BOTTOM = "#1C1B2A"
BORDER = "#2C2A44"
TITLE_FILL = "#8A87A8"
DEFAULT_FOREGROUND = "#EDEDF7"
DIM_OPACITY = 0.6

# The 16 named colours used by captured ANSI transcripts.
ANSI_16 = [
    "#22212F", "#FF5C7A", "#54E08A", "#FFC857", "#5BA8FF", "#C75CFF", "#46E5E0", "#EDEDF7",
    "#595972", "#FF8098", "#7BE8A6", "#FFD98A", "#8AC2FF", "#D98BFF", "#7BEDE9", "#FFFFFF",
]
CUBE_LEVELS = [0, 95, 135, 175, 215, 255]


def colour_256(index: int) -> str:
    if index < 16:
        return ANSI_16[index]
    if index < 232:
        index -= 16
        red, green, blue = index // 36, index // 6 % 6, index % 6
        return "#%02X%02X%02X" % (CUBE_LEVELS[red], CUBE_LEVELS[green], CUBE_LEVELS[blue])
    grey = 8 + 10 * (index - 232)
    return "#%02X%02X%02X" % (grey, grey, grey)


# --- cell widths ------------------------------------------------------------------------------------------

# One cell per code point, two for the East Asian wide/fullwidth ranges and the wide emoji blocks.
# Mirroring the terminal cell model keeps captured frame borders aligned in the SVG.
WIDE_RANGES = [
    (0x1100, 0x115F), (0x231A, 0x231B), (0x2329, 0x232A), (0x23E9, 0x23EC), (0x23F0, 0x23F0),
    (0x23F3, 0x23F3), (0x25FD, 0x25FE), (0x2614, 0x2615), (0x2648, 0x2653), (0x267F, 0x267F),
    (0x2693, 0x2693), (0x26A1, 0x26A1), (0x26AA, 0x26AB), (0x26BD, 0x26BE), (0x26C4, 0x26C5),
    (0x26CE, 0x26CE), (0x26D4, 0x26D4), (0x26EA, 0x26EA), (0x26F2, 0x26F3), (0x26F5, 0x26F5),
    (0x26FA, 0x26FA), (0x26FD, 0x26FD), (0x2705, 0x2705), (0x270A, 0x270B), (0x2728, 0x2728),
    (0x274C, 0x274C), (0x274E, 0x274E), (0x2753, 0x2755), (0x2757, 0x2757), (0x2795, 0x2797),
    (0x27B0, 0x27B0), (0x27BF, 0x27BF), (0x2B1B, 0x2B1C), (0x2B50, 0x2B50), (0x2B55, 0x2B55),
    (0x2E80, 0x303E), (0x3041, 0x33FF), (0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xA000, 0xA4CF),
    (0xA960, 0xA97F), (0xAC00, 0xD7A3), (0xF900, 0xFAFF), (0xFE10, 0xFE19), (0xFE30, 0xFE6F),
    (0xFF00, 0xFF60), (0xFFE0, 0xFFE6), (0x1F004, 0x1F004), (0x1F0CF, 0x1F0CF), (0x1F18E, 0x1F18E),
    (0x1F191, 0x1F19A), (0x1F200, 0x1F251), (0x1F300, 0x1F64F), (0x1F680, 0x1F6FF), (0x1F900, 0x1F9FF),
    (0x1FA70, 0x1FAFF), (0x20000, 0x3FFFD),
]


def cell_width(char: str) -> int:
    point = ord(char)
    return 2 if any(low <= point <= high for low, high in WIDE_RANGES) else 1


# --- box drawing ------------------------------------------------------------------------------------------

STROKE = 1.3
HEAVY_STROKE = 2.6
CORNER_RADIUS = 3.0

# glyph -> (up, down, left, right, heavy)
BOX_LINES = {
    "│": (True, True, False, False, False),
    "┃": (True, True, False, False, True),
    "─": (False, False, True, True, False),
    "━": (False, False, True, True, True),
    "╭": (False, True, False, True, False),
    "╮": (False, True, True, False, False),
    "╰": (True, False, False, True, False),
    "╯": (True, False, True, False, False),
    "┌": (False, True, False, True, False),
    "┐": (False, True, True, False, False),
    "└": (True, False, False, True, False),
    "┘": (True, False, True, False, False),
    "├": (True, True, False, True, False),
    "┤": (True, True, True, False, False),
    "┬": (False, True, True, True, False),
    "┴": (True, False, True, True, False),
    "┼": (True, True, True, True, False),
}
ROUNDED = set("╭╮╰╯")

# glyph -> (x offset, width, y offset, height, opacity), as fractions of the cell
BOX_BLOCKS = {
    "█": (0.0, 1.0, 0.0, 1.0, 1.0),
    "▌": (0.0, 0.5, 0.0, 1.0, 1.0),
    "▐": (0.5, 0.5, 0.0, 1.0, 1.0),
    "▀": (0.0, 1.0, 0.0, 0.5, 1.0),
    "▄": (0.0, 1.0, 0.5, 0.5, 1.0),
    "░": (0.0, 1.0, 0.0, 1.0, 0.30),
    "▒": (0.0, 1.0, 0.0, 1.0, 0.50),
    "▓": (0.0, 1.0, 0.0, 1.0, 0.75),
}
BOX_GLYPHS = set(BOX_LINES) | set(BOX_BLOCKS)


# --- the screen model -------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Style:
    foreground: str | None = None
    background: str | None = None
    bold: bool = False
    dim: bool = False
    italic: bool = False
    underline: bool = False
    reverse: bool = False

    def paint_colours(self) -> tuple[str, str | None]:
        """The (text fill, cell background or None) pair after applying reverse video."""
        foreground = self.foreground or DEFAULT_FOREGROUND
        if self.reverse:
            return (self.background or BACKGROUND), foreground
        return foreground, self.background


DEFAULT_STYLE = Style()
CONTINUATION = ""  # the second cell of a wide character


@dataclass
class Cell:
    char: str = " "
    style: Style = DEFAULT_STYLE

    def is_blank(self) -> bool:
        return self.char in (" ", CONTINUATION) and self.style.background is None and not self.style.reverse


class Screen:
    """An unbounded grid that transcripts are replayed onto; only what the renderer needs, not a VT emulator."""

    def __init__(self) -> None:
        self.rows: list[list[Cell]] = [[]]
        self.row = 0
        self.column = 0
        self.style = DEFAULT_STYLE

    # -- cursor ---------------------------------------------------------------------------------------------

    def _line(self) -> list[Cell]:
        while len(self.rows) <= self.row:
            self.rows.append([])
        line = self.rows[self.row]
        while len(line) <= self.column:
            line.append(Cell())
        return line

    def newline(self) -> None:
        self.row += 1
        self.column = 0
        self._line()

    def put(self, char: str) -> None:
        width = cell_width(char)
        line = self._line()
        while len(line) < self.column + width:
            line.append(Cell())
        line[self.column] = Cell(char, self.style)
        if width == 2:
            line[self.column + 1] = Cell(CONTINUATION, self.style)
        self.column += width

    def erase_in_line(self, mode: int) -> None:
        line = self._line()
        if mode == 0:
            del line[self.column:]
        elif mode == 1:
            for index in range(min(self.column + 1, len(line))):
                line[index] = Cell()
        else:
            line.clear()

    def erase_in_display(self, mode: int) -> None:
        if mode == 0:
            self.erase_in_line(0)
            del self.rows[self.row + 1:]
        elif mode == 1:
            for index in range(self.row):
                self.rows[index] = []
            self.erase_in_line(1)
        else:
            self.rows = [[]]
            self.row = 0
            self.column = 0

    # -- content --------------------------------------------------------------------------------------------

    def trimmed(self) -> list[list[Cell]]:
        rows = list(self.rows)
        while rows and all(cell.is_blank() for cell in rows[-1]):
            rows.pop()
        for line in rows:
            while line and line[-1].is_blank():
                line.pop()
        return rows


# --- the transcript parser --------------------------------------------------------------------------------


def parameters(text: str) -> list[int]:
    return [int(part) if part.isdigit() else 0 for part in text.split(";")] if text else []


def numbers(pieces: list[str]) -> list[int]:
    return [int(piece) if piece.isdigit() else 0 for piece in pieces]


def apply_sgr(style: Style, raw: str) -> Style:
    """One SGR parameter string: `;`-separated codes, with `38:2::r:g:b`-style sub-parameters tolerated."""
    tokens = (raw or "0").split(";")
    index = 0
    while index < len(tokens):
        token = tokens[index]
        index += 1
        if ":" in token:  # a self-contained extended colour, e.g. 38:5:81 or 38:2::255:95:175
            code, *sub = numbers(token.split(":"))
            style = with_extended_colour(style, code, extended_colour(sub, colon_form=True)[0])
            continue
        code = numbers([token])[0]
        if code in (38, 48):
            colour, used = extended_colour(numbers(tokens[index:index + 4]), colon_form=False)
            index += used
            style = with_extended_colour(style, code, colour)
        else:
            style = with_simple_attribute(style, code)
    return style


def with_simple_attribute(style: Style, code: int) -> Style:
    if code == 0:
        return DEFAULT_STYLE
    if code == 1:
        return replace(style, bold=True)
    if code == 2:
        return replace(style, dim=True)
    if code == 3:
        return replace(style, italic=True)
    if code == 4:
        return replace(style, underline=True)
    if code == 7:
        return replace(style, reverse=True)
    if code == 22:
        return replace(style, bold=False, dim=False)
    if code == 23:
        return replace(style, italic=False)
    if code == 24:
        return replace(style, underline=False)
    if code == 27:
        return replace(style, reverse=False)
    if 30 <= code <= 37:
        return replace(style, foreground=ANSI_16[code - 30])
    if code == 39:
        return replace(style, foreground=None)
    if 40 <= code <= 47:
        return replace(style, background=ANSI_16[code - 40])
    if code == 49:
        return replace(style, background=None)
    if 90 <= code <= 97:
        return replace(style, foreground=ANSI_16[code - 90 + 8])
    if 100 <= code <= 107:
        return replace(style, background=ANSI_16[code - 100 + 8])
    return style


def with_extended_colour(style: Style, code: int, colour: str | None) -> Style:
    if colour is None:
        return style
    return replace(style, foreground=colour) if code == 38 else replace(style, background=colour)


def extended_colour(values: list[int], colon_form: bool) -> tuple[str | None, int]:
    """Decode what follows a 38/48: returns (colour, how many `;` parameters it consumed)."""
    if len(values) >= 2 and values[0] == 5:
        return colour_256(max(0, min(255, values[1]))), 2
    if len(values) >= 4 and values[0] == 2:
        # The colon form may carry an (empty) colour-space id before the channels: 38:2::r:g:b.
        channels = values[2:5] if colon_form and len(values) >= 5 else values[1:4]
        red, green, blue = (max(0, min(255, channel)) for channel in channels)
        return "#%02X%02X%02X" % (red, green, blue), 4
    return None, 0


def replay(text: str) -> Screen:
    screen = Screen()
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == "\x1b":
            index = handle_escape(screen, text, index)
            continue
        index += 1
        if char == "\r":
            screen.column = 0
        elif char == "\n":
            screen.newline()
        elif char == "\t":
            screen.column += 8 - screen.column % 8
        elif char == "\b":
            screen.column = max(0, screen.column - 1)
        elif char < " " or char == "\x7f":
            continue
        else:
            screen.put(char)
    return screen


def handle_escape(screen: Screen, text: str, start: int) -> int:
    """Consume one escape sequence starting at `start`; returns the index just past it."""
    length = len(text)
    if start + 1 >= length:
        return length
    kind = text[start + 1]
    if kind == "[":
        index = start + 2
        while index < length and "\x20" <= text[index] <= "\x3f":
            index += 1
        final = text[index] if index < length else ""
        csi(screen, text[start + 2:index], final)
        return index + 1
    if kind == "]":
        # Operating-system command: ends with BEL or ST.
        bell = text.find("\x07", start)
        string_terminator = text.find("\x1b\\", start)
        ends = [end for end in (bell, string_terminator) if end != -1]
        if not ends:
            return length
        end = min(ends)
        return end + (1 if end == bell else 2)
    if kind in "()*+":
        return start + 3
    return start + 2


def csi(screen: Screen, body: str, final: str) -> None:
    if body.startswith("?") or body.startswith(">"):
        return  # private modes: alternate screen, cursor visibility, bracketed paste
    args = parameters(body)
    first = args[0] if args else 0
    if final == "m":
        screen.style = apply_sgr(screen.style, body)
    elif final in "Hf":
        row = (args[0] if len(args) > 0 and args[0] > 0 else 1) - 1
        column = (args[1] if len(args) > 1 and args[1] > 0 else 1) - 1
        screen.row, screen.column = row, column
        screen._line()
    elif final == "K":
        screen.erase_in_line(first)
    elif final == "J":
        screen.erase_in_display(first)
    elif final == "A":
        screen.row = max(0, screen.row - max(1, first))
    elif final == "B":
        screen.row += max(1, first)
        screen._line()
    elif final == "C":
        screen.column += max(1, first)
    elif final == "D":
        screen.column = max(0, screen.column - max(1, first))
    elif final == "G":
        screen.column = max(0, first - 1)


# --- the SVG ---------------------------------------------------------------------------------------------


def fmt(value: float) -> str:
    return ("%.2f" % value).rstrip("0").rstrip(".")


def box_glyph(char: str, x: float, y: float, fill: str, span: int = 1) -> str:
    if char in BOX_BLOCKS:
        dx, width, dy, height, opacity = BOX_BLOCKS[char]
        alpha = "" if opacity == 1.0 else ' fill-opacity="%s"' % opacity
        return '<rect x="%s" y="%s" width="%s" height="%s" fill="%s"%s shape-rendering="crispEdges"/>' % (
            fmt(x + dx * CELL_WIDTH), fmt(y + dy * LINE_HEIGHT), fmt((width + span - 1) * CELL_WIDTH),
            fmt(height * LINE_HEIGHT), fill, alpha,
        )
    up, down, left, right, heavy = BOX_LINES[char]
    stroke = HEAVY_STROKE if heavy else STROKE
    centre_x, centre_y = x + CELL_WIDTH / 2, y + LINE_HEIGHT / 2
    if char in ROUNDED:
        vertical_end = y if up else y + LINE_HEIGHT
        horizontal_end = x if left else x + CELL_WIDTH
        radius_y = centre_y + CORNER_RADIUS if up else centre_y - CORNER_RADIUS
        radius_x = centre_x - CORNER_RADIUS if left else centre_x + CORNER_RADIUS
        path = "M%s %s L%s %s Q%s %s %s %s L%s %s" % (
            fmt(centre_x), fmt(vertical_end), fmt(centre_x), fmt(radius_y), fmt(centre_x), fmt(centre_y),
            fmt(radius_x), fmt(centre_y), fmt(horizontal_end), fmt(centre_y),
        )
        return '<path d="%s" fill="none" stroke="%s" stroke-width="%s"/>' % (path, fill, stroke)
    arms = []
    if up or down:
        top = y if up else centre_y
        bottom = y + LINE_HEIGHT if down else centre_y
        arms.append('<rect x="%s" y="%s" width="%s" height="%s" fill="%s"/>' % (
            fmt(centre_x - stroke / 2), fmt(top), fmt(stroke), fmt(bottom - top), fill))
    if left or right:
        start = x if left else centre_x
        end = x + (span * CELL_WIDTH) if right else centre_x
        arms.append('<rect x="%s" y="%s" width="%s" height="%s" fill="%s"/>' % (
            fmt(start), fmt(centre_y - stroke / 2), fmt(end - start), fmt(stroke), fill))
    return "".join(arms)


def text_attributes(style: Style) -> str:
    attributes = []
    if style.bold:
        attributes.append(' font-weight="700"')
    if style.italic:
        attributes.append(' font-style="italic"')
    if style.underline:
        attributes.append(' text-decoration="underline"')
    if style.dim:
        attributes.append(' fill-opacity="%s"' % DIM_OPACITY)
    return "".join(attributes)


def render(rows: list[list[Cell]], title: str, min_cols: int) -> str:
    cols = max([min_cols, 1] + [len(line) for line in rows])
    row_count = max(1, len(rows))
    width = cols * CELL_WIDTH + PAD_X * 2
    height = row_count * LINE_HEIGHT + PAD_TOP + PAD_BOTTOM
    safe_title = escape(title, {'"': "&quot;"})

    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s" '
        'font-family="%s" font-size="%s" role="img" aria-label="%s">'
        % (fmt(width), fmt(height), fmt(width), fmt(height), FONT_FAMILY, fmt(FONT_SIZE), safe_title),
        "<title>%s</title>" % safe_title,
        '<defs><linearGradient id="chrome" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>'
        '<clipPath id="window"><rect x="0.5" y="0.5" width="%s" height="%s" rx="%s"/></clipPath></defs>'
        % (CHROME_TOP, CHROME_BOTTOM, fmt(width - 1), fmt(height - 1), fmt(RADIUS)),
        '<rect x="0.5" y="0.5" width="%s" height="%s" rx="%s" fill="%s" stroke="%s"/>'
        % (fmt(width - 1), fmt(height - 1), fmt(RADIUS), BACKGROUND, BORDER),
        '<g clip-path="url(#window)"><rect x="0" y="0" width="%s" height="%s" fill="url(#chrome)"/>'
        '<rect x="0" y="%s" width="%s" height="1" fill="%s"/></g>'
        % (fmt(width), fmt(TITLE_BAR), fmt(TITLE_BAR - 0.5), fmt(width), BORDER),
        '<circle cx="20" cy="15" r="5.5" fill="#FF5F57"/><circle cx="39" cy="15" r="5.5" fill="#FEBC2E"/>'
        '<circle cx="58" cy="15" r="5.5" fill="#28C840"/>',
        '<text x="%s" y="19.5" font-size="12" fill="%s" text-anchor="middle">%s</text>'
        % (fmt(width / 2), TITLE_FILL, safe_title),
    ]

    def cell_x(column: int) -> float:
        return PAD_X + column * CELL_WIDTH

    def cell_y(row: int) -> float:
        return PAD_TOP + row * LINE_HEIGHT - BASELINE

    # Cell backgrounds first, merged into horizontal runs.
    for y, line in enumerate(rows):
        x = 0
        while x < len(line):
            _, background = line[x].style.paint_colours()
            if background is None:
                x += 1
                continue
            run = x
            while run < len(line) and line[run].style.paint_colours()[1] == background:
                run += 1
            parts.append('<rect x="%s" y="%s" width="%s" height="%s" fill="%s"/>' % (
                fmt(cell_x(x)), fmt(cell_y(y)), fmt((run - x) * CELL_WIDTH), fmt(LINE_HEIGHT), background))
            x = run

    # Box-drawing and block glyphs as vectors; identical horizontal neighbours merge into one shape so
    # translucent blocks never double-paint a shared edge.
    for y, line in enumerate(rows):
        x = 0
        while x < len(line):
            cell = line[x]
            if cell.char not in BOX_GLYPHS:
                x += 1
                continue
            fill, _ = cell.style.paint_colours()
            run = x + 1
            mergeable = cell.char in ("─", "━") or (
                cell.char in BOX_BLOCKS and BOX_BLOCKS[cell.char][:2] == (0.0, 1.0))
            if mergeable:
                while run < len(line) and line[run].char == cell.char and line[run].style.paint_colours()[0] == fill:
                    run += 1
            parts.append(box_glyph(cell.char, cell_x(x), cell_y(y), fill, span=run - x))
            x = run

    # Text runs: consecutive cells with the same style, box glyphs excluded.
    for y, line in enumerate(rows):
        x = 0
        baseline = PAD_TOP + y * LINE_HEIGHT
        while x < len(line):
            cell = line[x]
            if cell.char in (" ", CONTINUATION) or cell.char in BOX_GLYPHS:
                x += 1
                continue
            run = x
            chars: list[str] = []
            while run < len(line) and line[run].style == cell.style and line[run].char not in BOX_GLYPHS:
                if line[run].char != CONTINUATION:
                    chars.append(line[run].char)
                run += 1
            text = "".join(chars).rstrip()
            if text:
                cells = sum(cell_width(char) for char in text)
                fill, _ = cell.style.paint_colours()
                parts.append(
                    '<text x="%s" y="%s" fill="%s" textLength="%s" lengthAdjust="spacingAndGlyphs" '
                    'xml:space="preserve"%s>%s</text>'
                    % (fmt(cell_x(x)), fmt(baseline), fill, fmt(cells * CELL_WIDTH),
                       text_attributes(cell.style), escape(text))
                )
            x = run

    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("transcript", help="file with the raw terminal output")
    parser.add_argument("output", help="SVG file to write")
    parser.add_argument("--title", default="", help="text in the window's title bar")
    parser.add_argument("--min-cols", type=int, default=0, help="never render narrower than this many columns")
    args = parser.parse_args(argv)

    with open(args.transcript, "rb") as handle:
        text = handle.read().decode("utf-8", errors="replace")
    rows = replay(text).trimmed()
    if not rows:
        print("render.py: the transcript has no visible content: %s" % args.transcript, file=sys.stderr)
        return 1
    svg = render(rows, args.title, args.min_cols)
    ElementTree.fromstring(svg)  # refuse to write anything that is not well-formed XML
    with open(args.output, "w", encoding="utf-8") as handle:
        handle.write(svg)
    cols = max([args.min_cols] + [len(line) for line in rows])
    print("rendered %s (%d cols x %d rows)" % (args.output, cols, len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

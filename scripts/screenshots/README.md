# Screenshot harness

The terminal screenshots in [`assets/screenshots/`](../../assets/screenshots) are generated from real runs of
the native binary, not drawn by hand. Nothing beyond Python 3's standard library is needed.

```bash
./mill --no-daemon show app.nativeImage   # once, unless a binary path is passed
./scripts/screenshots/refresh.sh          # rewrites assets/screenshots/*.svg
```

| File | Role |
| --- | --- |
| `refresh.sh` | Runs the binary for each shot and wires the two scripts below together. |
| `capture.py` | Runs a full-screen program in a pty, sends keys once it has painted a new frame, records the raw bytes it wrote. |
| `render.py` | Replays a recording or any ANSI-coloured transcript onto a cell grid and writes a self-contained SVG terminal window. |
| `fixtures/font-names.txt` | The first 20 lines of a real `--font-names` run; rendered when the live run cannot reach GitHub. |

## What each shot is

| Shot | Command | Notes |
| --- | --- | --- |
| `cli-help.svg` | `--help` | the parser's usage text, verbatim |
| `cli-dry-run.svg` | `--config config.example.yaml --dry-run` | `FORCE_COLOR=1` because stdout is a file; `HOME=/home/dev` so the destination reads like an ordinary machine |
| `cli-font-names.svg` | `--font-names \| head -20` | live when GitHub answers, otherwise the fixture |

The `$ command` prompt line at the top of each CLI shot is the one thing on screen the binary did not print.

## How `render.py` sizes and colours

- Width is the widest row in cells, height is the number of rows (trailing blank rows dropped); `--min-cols`
  stops a short listing from rendering as a sliver.
- Colours come from the SGR codes in the transcript: the 16 named colours, 256-colour cube and greys are
  computed, truecolour is used as given. Bold, italic, underline, dim and reverse video are honoured.
- `ESC[H`, `ESC[K` and `ESC[J` are replayed, so a program that repaints in place renders as its final frame;
  a `\r` overwrites the line the way a spinner does. Other sequences are skipped.
- Box-drawing and block characters are drawn as vectors so borders are continuous at any zoom.
- Text is placed with `textLength` per run, so the picture holds together with whichever monospace font
  the viewer has.

The renderer refuses to write an SVG that is not well-formed XML, and `refresh.sh` re-parses every file it
produced.

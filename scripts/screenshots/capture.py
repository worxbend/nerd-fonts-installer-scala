#!/usr/bin/env python3
"""Run a full-screen program in a pseudo-terminal, drive it with keystrokes, record what it drew.

The recording is the raw byte stream the program wrote (frames, colours, cursor and erase sequences), which
`render.py` replays into an SVG. Keys are sent one step at a time; each step waits until the program has
painted a new frame since the previous key (a frame starts with `ESC[H`, the cursor-home the picker
repaints from) and the output has then been quiet for a moment, so the capture is paced by the program
rather than by guessed sleeps. The recording ends when the program exits, or at the timeout.

Standard library only; no third-party packages.

Usage:
  capture.py --out FILE [--cols N] [--rows N] [--timeout SECONDS] [--idle SECONDS]
             [--env NAME=VALUE ...] [--unset NAME ...] [--stderr pty|null]
             [--send TEXT ...] -- COMMAND [ARG ...]

`--send` text is a Python string literal body: `\\r` is Enter, ` ` is Space, `\\x1b[B` is Down, `q` is q.
"""

from __future__ import annotations

import argparse
import codecs
import fcntl
import os
import pty
import select
import signal
import struct
import sys
import termios
import time

FRAME_MARKER = b"\x1b[H"


def child(command: list[str], environment: dict[str, str], stderr: str) -> None:
    if stderr == "null":
        devnull = os.open(os.devnull, os.O_WRONLY)
        os.dup2(devnull, 2)
        os.close(devnull)
    os.environ.clear()
    os.environ.update(environment)
    try:
        os.execvp(command[0], command)
    except OSError as error:
        sys.stderr.write("capture.py: cannot run %s: %s\n" % (command[0], error))
        os._exit(127)


def capture(command: list[str], environment: dict[str, str], cols: int, rows: int, steps: list[bytes],
            timeout: float, idle: float, stderr: str) -> tuple[bytes, int | None]:
    pid, master = pty.fork()
    if pid == 0:
        child(command, environment, stderr)
    fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

    recorded = bytearray()
    pending = list(steps)
    frames_at_last_send = 0
    last_output = time.monotonic()
    deadline = last_output + timeout
    closed = False  # the slave side went away, i.e. the program exited

    try:
        while not closed and time.monotonic() < deadline:
            readable, _, _ = select.select([master], [], [], 0.05)
            if readable:
                try:
                    data = os.read(master, 65536)
                except OSError:
                    data = b""
                if not data:
                    closed = True
                    continue
                recorded.extend(data)
                last_output = time.monotonic()
                continue
            if not pending:
                continue
            frames = recorded.count(FRAME_MARKER)
            quiet = time.monotonic() - last_output >= idle
            if frames > frames_at_last_send and quiet:
                os.write(master, pending.pop(0))
                frames_at_last_send = frames
    finally:
        os.close(master)

    if not closed:
        os.kill(pid, signal.SIGKILL)
    _, status = os.waitpid(pid, 0)
    return bytes(recorded), os.waitstatus_to_exitcode(status)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", required=True, help="file to write the raw recording to")
    parser.add_argument("--cols", type=int, default=112)
    parser.add_argument("--rows", type=int, default=34)
    parser.add_argument("--timeout", type=float, default=90.0, help="give up after this many seconds")
    parser.add_argument("--idle", type=float, default=0.6, help="quiet time required before the next key")
    parser.add_argument("--env", action="append", default=[], metavar="NAME=VALUE")
    parser.add_argument("--unset", action="append", default=[], metavar="NAME")
    parser.add_argument("--stderr", choices=("pty", "null"), default="pty",
                        help="where the program's stderr goes; `null` keeps pre-frame notices out of the recording")
    parser.add_argument("--send", action="append", default=[], metavar="TEXT", help="a key step (escaped)")
    parser.add_argument("command", nargs=argparse.REMAINDER, help="-- COMMAND [ARG ...]")
    args = parser.parse_args(argv)

    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("no command given after --")

    environment = dict(os.environ)
    environment.update({"TERM": "xterm-256color", "COLORTERM": "truecolor", "LC_ALL": "C.UTF-8"})
    for name in args.unset:
        environment.pop(name, None)
    for assignment in args.env:
        name, _, value = assignment.partition("=")
        environment[name] = value

    steps = [codecs.decode(text, "unicode_escape").encode("utf-8") for text in args.send]
    recorded, exit_status = capture(command, environment, args.cols, args.rows, steps, args.timeout,
                                    args.idle, args.stderr)
    with open(args.out, "wb") as handle:
        handle.write(recorded)
    frames = recorded.count(FRAME_MARKER)
    print("captured %s: %d bytes, %d frames, exit %s" % (args.out, len(recorded), frames, exit_status))
    if frames == 0:
        print("capture.py: the program never painted a frame", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

# AGENTS.md — operating guide for coding agents

Applies to Claude Code, Codex, and any other agent working in this repository.

## Read first

1. [`docs/SPEC.md`](docs/SPEC.md) — the behavioural and architectural contract. Parity with the Go
   reference (`worxbend/nerd-fonts-installer`) is a requirement, not a suggestion.
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module map, invariants and the decision log. Update it
   in the same change whenever a boundary, contract or invariant moves.

## How to work here

- **Build:** Mill via the wrapper. From automation use `./mill --no-daemon <task>`.

  | Task | Command |
  | --- | --- |
  | Compile everything | `./mill --no-daemon __.compile` |
  | Run all tests | `./mill --no-daemon __.test` |
  | One module's tests | `./mill --no-daemon core.test` |
  | Format | `./mill --no-daemon mill.scalalib.scalafmt/reformatAll` |
  | Format check | `./mill --no-daemon mill.scalalib.scalafmt/checkFormatAll` |
  | Scalafix | `./mill --no-daemon __.fix` (check with `--check`) |
  | Native binary | `./mill --no-daemon show app.nativeImage` |
  | Run from source | `./mill --no-daemon app.run -- --help` |

  The toolchain is GraalVM Community for JDK 25, fetched by Mill; no `GRAALVM_HOME` needed.
- **Definition of done for any change:** compiles with `-Werror`, all tests pass, formatted, scalafix clean,
  `docs/ARCHITECTURE.md` updated if a contract moved.
- **Style:** direct-style Scala 3 (braceless syntax, explicit return types on public members, opaque types and
  enums for domain values, `Either` with sealed error ADTs for recoverable failures, Ox for concurrency, no
  class-level `var`, no `null`, no `return`, no `while`). Picocli command classes are the one sanctioned place
  for `var` fields (setters are how picocli binds options); annotate them with
  `@SuppressWarnings(Array("scalafix:DisableSyntax.var"))` and a one-line reason.
- **Boundaries:** `app -> cli -> {core, config, picker}`, `config -> core`, `picker -> core`. Core never imports
  picocli, fansi, or terminal code.
- **Security invariants (never weaken):** every family name passes `FamilyName.parse` before touching a path or
  URL; downloads and extraction are byte-capped; checksum mismatch is fatal; installs are staged then renamed.
- **Tests:** munit, one scenario per test, named as a sentence. Fake the ports (`HttpClient`, `Terminal`,
  `ProcessRunner`, `Environment`); never hit the network in tests.
- **Commits:** Conventional Commits, small and individually green. Add the `Co-Authored-By` trailer your
  harness provides. Branch off `main`; only push or open PRs when asked.

## Skills

- Direct-style Scala guidance: the `direct-style-scala` skill (Ox, error handling, code organisation).
- Structural review vocabulary: `.claude/skills/exodes-skills-workspace-refactoring-guru/SKILL.md`
  (code smells, refactoring techniques, pattern selection). Use its names in reviews.

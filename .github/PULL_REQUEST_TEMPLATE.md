## Summary

<!-- What changes and why. Link the issue if there is one. -->

## Behaviour change

<!-- Does user-visible behaviour change? If so, describe the new behaviour and update docs/SPEC.md
     (the behavioural contract) in the same change. -->

## Checklist

The gate (`AGENTS.md`), run locally with `./mill --no-daemon`:

- [ ] `__.compile` is clean (`-Werror` is on)
- [ ] `__.test` passes
- [ ] `mill.scalalib.scalafmt/checkFormatAll` passes
- [ ] `__.fix --check` passes
- [ ] `docs/ARCHITECTURE.md` is updated if a boundary, contract or invariant moved
- [ ] `CHANGELOG.md` `Unreleased` is updated for anything a user would notice

Boundaries and invariants:

- [ ] Module edges still hold (`app -> cli -> {core, config}`, `config -> core`; `core` imports no fansi or terminal code)
- [ ] No new class-level `var`, `null`, `return` or `while`; no `sys.env`/`sys.props` below the composition root
- [ ] The security invariants are untouched or this PR says how they are strengthened: `FamilyName.parse` before any path or URL, byte caps on downloads and extraction, checksum mismatch fatal, staged-then-renamed installs (`docs/SECURITY.md`)
- [ ] New ports come with a fake next to their tests; no test touches the network

Commits:

- [ ] Conventional Commits, each individually green
- [ ] If the native image or reflection is affected: `./mill --no-daemon show app.nativeImage` was run and the binary's `--version`, `--help` and `--dry-run` were checked

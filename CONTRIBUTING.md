# Contributing to qkt-venue-gateway

## Before you write code

- A change to what the gateway promises qkt starts in the wire spec, [docs/vgp-v1-wire.md](docs/vgp-v1-wire.md).
  The authoritative copy lives in the [qkt](https://github.com/elitekaycy/qkt) repository
  (`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`); change both together.
- A design change goes in [docs/design.md](docs/design.md); plans for larger work go under `docs/plans/`.
- A new venue is a new module, `adapter-<venue>`; read "Writing an adapter" in the README and
  [docs/design.md](docs/design.md) §3 first.

## Rules

- Kotlin 2.1, JVM 21. Files at most 200 lines (tests 220). KDoc on every public type and member. ktlint clean.
- Money and quantities are `BigDecimal`, parsed from the raw JSON text, never through `Double`.
- The host never imports a venue API; an adapter sees only `adapter-api`. The build enforces both.
- Every wire-spec statement the host implements has a test. Every venue fact an adapter relies on has a
  fixture recorded from the venue (`src/test/resources/fixtures`), with account identifiers removed.
- An adapter passes `AdapterContractTest` against the venue's test environment before it merges.
- Never commit a secret: they reach the gateway as `GATEWAY_*` variables or `_FILE` paths; `.env` is untracked.

## Commits

Conventional Commits, subject only, at most 70 characters, imperative and lowercase:

```
feat(host): serve order changes and position closes
fix(deribit): fetch klines in spans under deribit's silent 5001 cap
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `chore`. Scopes: `host`, `wire`, `api`,
`testkit`, `deribit`, `paper`, `app`, `build`, `ci`, `docs`. No emoji, no generated-by footers.

## Before a pull request

```bash
./gradlew build
```

It must pass, and `git status` must be clean. Describe in the pull request what changed, why, and how it
was tested (offline fixtures, the contract suite against a test account, or both).

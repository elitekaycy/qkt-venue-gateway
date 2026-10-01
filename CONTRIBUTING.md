# Contributing to qkt-venue-gateway

The rules are in [CLAUDE.md](CLAUDE.md) (code, structure, tests, commits) and, for venues,
[docs/adapters.md](docs/adapters.md). CI enforces them; this page is the flow.

## Before you write code

- A change to what the gateway promises qkt starts in the wire spec, [docs/vgp-v1-wire.md](docs/vgp-v1-wire.md).
  The authoritative copy lives in the [qkt](https://github.com/elitekaycy/qkt) repository
  (`docs/superpowers/specs/2026-10-01-vgp-v1-wire.md`); change both together.
- A design change goes in [docs/design.md](docs/design.md); plans for larger work go under `docs/plans/`.
- A new venue is a new module on its own branch, `adapter/<venue>`: follow [docs/adapters.md](docs/adapters.md).

## Branches

`<type>/<topic>` from `main` (`feat/quote-throttle`, `fix/stream-replay-gap`), one change each, or
`adapter/<venue>` for venue work. Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`,
`ci`, `chore`. Nobody pushes to `main`.

## Commits

Conventional Commits, subject only, at most 72 characters, imperative, starting lowercase, no period:

```
feat(host): serve order changes and position closes
fix(deribit): fetch klines in spans under deribit's silent 5001 cap
```

Scopes: `host`, `wire`, `api`, `testkit`, `app`, `<venue>`, `build`, `ci`, `docs`. No emoji, no
generated-by or tool co-author lines.

## Before a pull request

```bash
./gradlew build
scripts/check-rules.sh
scripts/check-adapter.sh <venue>     # if an adapter changed
```

They must pass, and `git status` must be clean. Fill in the pull request template: what changed, why,
and how it was tested.

## Checks

| Workflow | Job | Runs | Checks |
|---|---|---|---|
| `check` | `build` | every PR, push to main | compile, ktlint, every module's tests (offline) |
| `check` | `rules` | every PR, push to main | `scripts/check-rules.sh`, shellcheck |
| `check` | `image` | every PR, push to main | `docker compose up` the paper gateway, wait for healthy, call it |
| `pr` | `pr` | every PR | branch name, title, commits, adapter branch scope (`scripts/check-pr.sh`) |
| `adapter` | `adapter` | PRs touching an adapter; nightly | layout, tests, contract suite on the venue's test environment |

`build` and `image` skip on docs-only changes; `adapter` passes when no adapter is touched.

## Merging

Pull requests merge into `main` by squash only, with every check above green and every review thread
resolved; the PR title becomes the commit. A maintainer applies this once by importing
[`.github/rulesets/main.json`](.github/rulesets/main.json) under Settings → Rules → Rulesets → Import.

## Venue secrets

The `adapter` workflow reads test-environment keys from repository secrets named
`<VENUE>_CLIENT_ID` and `<VENUE>_CLIENT_SECRET` (today `DERIBIT_CLIENT_ID`, `DERIBIT_CLIENT_SECRET`, a
Deribit **testnet** key with read and trade scopes). Pull requests from forks get no secrets, so a
maintainer re-runs their adapter check from a branch in this repository.

# qkt-venue-gateway

The VGP v1 gateway for qkt: one process (one container) per venue account. qkt and guardrails talk to it
over the wire protocol in `docs/vgp-v1-wire.md` (qkt holds the authoritative copy); it talks to the
venue through one adapter. Docs index: `docs/README.md` (running, adapters, wire, design). Plans:
`docs/plans/`. Behaviour changes update the docs in the same PR.

Lean and production-first: the smallest change that is correct, tested and documented. No speculative
abstractions, no dead code, no TODOs left behind, no new dependency without a reason in the PR.

## Structure (enforced by the build and CI)

| Module | Owns | May depend on |
|---|---|---|
| `vgp-wire` | wire DTOs | nothing in the repo |
| `adapter-api` | `VenueAdapter` and the neutral types | nothing in the repo |
| `adapter-testkit` | `AdapterContractTest` | `adapter-api` |
| `host` | everything qkt sees: auth, journal, orders, stream, reconciler, kill switch, quotes, bars | `vgp-wire`, `adapter-api` |
| `adapter-<venue>` | one venue: `client/`, mapping, adapter, factory | `adapter-api` (another adapter's `client` at most) |
| `app` | `GATEWAY_*` config, adapter loading, startup | everything |

- `host` never imports a venue API; an adapter never imports `host`, `vgp-wire` or `app`.
- An adapter's `client` package speaks the venue in the venue's words and knows nothing of VGP; its
  mapping is the only place venue words become `adapter-api` types.
- Configuration is `GATEWAY_*` environment variables only (`GatewayConfig`): a default lives there or
  the variable is required. Never add a config file or a second source of defaults.

## Code

- Kotlin 2.1, JVM 21, ktlint 1.5 clean (`./gradlew ktlintFormat` fixes most).
- Files ≤ 200 lines in `src/main`, ≤ 220 in `src/test`. Split by responsibility, not by size alone.
- KDoc on every public type and member: what it is and the rule it keeps, not how.
- Money and quantities are `BigDecimal` parsed from the raw JSON text. `Double` and `Float` never
  appear in `src/main`.
- Fail loudly naming the input at fault (`GATEWAY_LISTEN must be <host>:<port>`); never swallow an
  exception or return a silent default for a value qkt trades on.
- Never log a secret, a token or an `Authorization` header. `Credentials.toString()` masks the secret.

## Tests

- Every wire-spec statement the host implements has a test; every bug fixed gets the test that
  would have caught it.
- Venue facts come from fixtures recorded from the venue (`src/test/resources/fixtures`), account ids
  removed. No hand-written venue JSON.
- Tests are offline by default. Live tests (`AdapterContractTest`, probes) skip without their venue
  key locally and run in the adapter workflow, where skipping fails.
- Name tests as sentences of behaviour: `` `a missing token is refused by name` ``.

## Branches, commits and pull requests

- Branch from `dev`, one change per branch, named `<type>/<short-kebab-topic>` (`feat/quote-throttle`,
  `fix/stream-replay-gap`). Types: `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`,
  `chore`. A new venue, or work only on one venue, is `adapter/<venue>` (see `docs/adapters.md`).
  Session branches named `claude/…` are accepted.
- Commits: Conventional Commits, subject only, ≤ 72 characters, imperative and lowercase, no trailing
  period: `feat(host): serve order changes and position closes`. Scopes: `host`, `wire`, `api`,
  `testkit`, `app`, `<venue>`, `build`, `ci`, `docs`.
- Author and committer: `Dickson Anyaele <dicksonanyaele1234@gmail.com>`. No tool or AI attribution of any
  kind (no `Co-Authored-By` for tools, no "Generated with"); a session link, when attached, is one line
  `session(<type>): <link>`.
- PRs target `dev` and merge by squash, with every required check green; the PR title follows the
  commit rules (it becomes the squash commit). `main` only moves by promoting `dev`, which cuts the
  release (version from the commits, `CHANGELOG.md`, tag). Never edit `VERSION` or `CHANGELOG.md` in
  a PR. See `CONTRIBUTING.md`, "Merging" and "Releases".

## Before you push

```bash
./gradlew build                 # compile, ktlint, every module's tests
scripts/check-rules.sh          # file sizes, no Double in src/main, no committed secrets or state
scripts/check-adapter.sh <venue> # when an adapter changed
```

CI runs the same commands; a red check is fixed at its root cause, never skipped or disabled.

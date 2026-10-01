# Adapters: rules and the path to a new venue

An adapter translates one venue's API into `adapter-api` and nothing more. Everything a venue does not
decide (journal, idempotency, recovery from fills, kill switch, quote refresh) is the host's, written
once. The interface and its rules are in [design.md](design.md) §3; this page is how an adapter is built,
tested and merged. It is strict on purpose: an adapter that passes it works with qkt as it is.
For a walk-through with code, see [writing-an-adapter.md](writing-an-adapter.md).

## The branch

- One venue, one branch: `adapter/<venue>`, lowercase letters and digits only (`adapter/bybit`).
- The branch carries the whole adapter, nothing else. It may change only:
  `adapter-<venue>/**`, `settings.gradle.kts`, `app/build.gradle.kts`, `README.md`, `docs/adapters.md`
  and `.github/workflows/adapter.yml` (to pass the venue's secrets). A host or API change it needs is
  its own `feat/…` PR, merged first.
- CI enforces both (`pr` workflow). Fixes to an existing adapter use the same branch name.

## The module

```
adapter-<venue>/
  build.gradle.kts            api(project(":adapter-api")), testImplementation(project(":adapter-testkit"))
  README.md                   settings, credentials, venue facts (see below)
  CLAUDE.md                   @README.md and @../docs/adapters.md, nothing else
  src/main/kotlin/com/qkt/venuegateway/<venue>/
    client/                   the venue's protocol in its own words; no adapter-api types
    <Venue>Mapping*.kt        the only place venue words become adapter-api types
    <Venue>Adapter.kt         implements VenueAdapter
    <Venue>AdapterFactory.kt  type = "<venue>"; reads settings and credentials, fails naming the key
    <Venue>Settings.kt        every setting, its default, or why it has none
  src/main/resources/META-INF/services/com.qkt.venuegateway.adapter.VenueAdapterFactory
  src/test/kotlin/…           offline tests and <Venue>ContractTest
  src/test/resources/fixtures/ responses recorded from the venue, account ids removed
```

- Depends on `adapter-api` only (another adapter's `client` package at most, as `paper` reuses
  Deribit's public client). Never `host`, `vgp-wire` or `app`.
- Settings are `GATEWAY_SETTING_<KEY>` at run time; credentials are `GATEWAY_LOGIN`/`GATEWAY_SECRET`.
  A setting that chooses real money over a test environment has no default.
- No threads, clocks or env reads of its own beyond what `AdapterContext` hands in.

## Checkpoints, in order

Each checkpoint is one or more commits on the branch; do not start the next with the last one red.

1. **Probe.** Call the venue's test environment by hand; record every response the adapter will read
   into `fixtures/`. Write the facts you measured (limits, id formats, how long closed orders stay
   queryable, rounding, error codes) into the module README.
2. **Client.** The venue protocol, tested offline against the fixtures (and `MockWebServer` for
   sockets). No `adapter-api` import in `client/`.
3. **Mapping.** Venue → `adapter-api` types, every field from a fixture. Money is `BigDecimal` from the
   raw JSON text. Unknown enum values fail loudly.
4. **Adapter and factory.** Registered under `META-INF/services`; refuses bad settings naming the key.
5. **Contract.** `<Venue>ContractTest extends AdapterContractTest` passes against the test environment.
   It reads its key from `<VENUE>_CLIENT_ID` / `<VENUE>_CLIENT_SECRET` and skips locally without them.
6. **Wire-up.** `settings.gradle.kts` includes the module; `app/build.gradle.kts` bundles it; the README
   adapter table and settings table list it; `adapter.yml` passes its secrets.
7. **End to end.** qkt trades through a gateway running the adapter on the test environment: an order
   placed, filled, seen on the stream, and closed. Note the run in the PR.

## Tests the adapter must have

| Test | Runs | Proves |
|---|---|---|
| client tests | offline, fixtures | requests are built and answers read as the venue sends them |
| mapping tests | offline, fixtures | every adapter-api field, decimals exact, unknown values refused |
| settings tests | offline | defaults, required keys, bad values refused by name |
| `<Venue>ContractTest` | test environment | the behaviour the host relies on (`adapter-testkit`) |

## CI

The `adapter` workflow runs for every PR touching an adapter, and nightly for all of them:

1. `scripts/check-adapter.sh <venue>`: the layout above is complete (module, README, CLAUDE.md, service
   registration, contract test, fixtures, settings and app inclusion, README row).
2. `./gradlew :adapter-<venue>:test` with the venue's secrets, then a check that the contract suite
   **ran**: on this workflow a skipped contract test is a failure. Secrets are repository secrets named
   `<VENUE>_CLIENT_ID` and `<VENUE>_CLIENT_SECRET`, test-environment keys with trade scope only.
3. Nightly, the same against `main`, so a venue that changes under us turns red before qkt notices.

## Done means

All checkpoints green, the adapter workflow green with the contract suite run, the end-to-end run noted
in the PR, and nothing in the diff outside the branch scope.

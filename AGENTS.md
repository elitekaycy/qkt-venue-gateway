# qkt-venued

The VGP v1 gateway host for qkt: one process per venue account. qkt and guardrails talk to it over
the wire protocol in `docs/vgp-v1-wire.md` (the qkt repository holds the authoritative copy); it talks
to the venue through one adapter. Design: `docs/design.md`. Plans: `docs/plans/`.

## Module boundaries (enforced by the build)
- `host` serves everything qkt sees and never imports a venue API.
- Adapters (`adapter-*`) see only `adapter-api` and their venue client; they never speak HTTP to qkt.
- `deribit-client` knows Deribit's JSON-RPC and nothing about VGP.

## Rules
- Kotlin 2.1, JVM 21. Files ≤ 200 lines (tests ≤ 220), KDoc on public API, ktlint clean.
- Money and quantities are `BigDecimal`, parsed from raw JSON text, never through `Double`.
- Every wire-spec statement the host implements has a test; venue facts have recorded fixtures.
- Build: `./gradlew build`. One module: `./gradlew :host:test`.

## Commits
- Author and committer: `Dickson Anyaele <dicksonanyaele1234@gmail.com>`.
- Conventional subject (`feat(host): …`), no body needed, no reference to Claude or AI anywhere; a
  session link, when attached, is one line `session(<type>): <link>`.

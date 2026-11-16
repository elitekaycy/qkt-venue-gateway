# Event instrument kind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the gateway can list binary event contracts (`kind: event`) with their series, event, strike, times,
occasion and fee shape, byte-identically for every instrument listed today.

**Architecture:** one enum value and one optional nested `EventTerms` on `adapter-api` `Instrument`; flat optional
snake_case fields on `WireInstrument` (absent when null); the contract kit learns the event shape. Follows the
equity precedent (gateway #85).

**Tech Stack:** Kotlin, kotlinx.serialization, JUnit 5, AssertJ.

**Spec:** `docs/plans/2026-11-06-event-contracts-and-kalshi.md` §3.1.

## Global Constraints

- Files ≤ 200 lines in `src/main`, ≤ 220 in `src/test` (`scripts/check-rules.sh`).
- No `Double` in `src/main`; decimals are `BigDecimal` in the API and plain strings on the wire.
- Every instrument a venue lists today serializes byte for byte the same (new fields absent when null).
- Commits: `<type>(<scope>): <subject>`, ≤ 72 chars, scopes `api`, `wire`, `host`, `testkit`, `docs`.
- Gradle only through `flock /var/tmp/qkt-work/jvm.lock ./gradlew --no-daemon …`, and only when the shared laptop
  allows builds; otherwise CI on the draft PR.

## Review Focus

- An existing perpetual/option/equity listing gains no field → pinned by the existing `EquityInstrumentWireTest`
  plus a new case in `EventInstrumentWireTest`.
- Booleans `can_close_early`/`exclusive` absent when false? No: absent only when the instrument is not an event
  (`event == null`); for an event they are always written, so `false` is distinguishable from "not an event".
- A `between` contract missing `cap` → the kit refuses it by name.
- `outcome` 0 (resolved NO) is written as `"0"`, not dropped.
- A `none` strike type with a `floor` → the kit refuses it.

---

### Task 1: Event terms in the adapter API

**Files:**
- Create: `adapter-api/src/main/kotlin/com/qkt/venuegateway/adapter/VenueEvent.kt`
- Modify: `adapter-api/src/main/kotlin/com/qkt/venuegateway/adapter/VenueEnums.kt` (add `EVENT`)
- Modify: `adapter-api/src/main/kotlin/com/qkt/venuegateway/adapter/VenueAccount.kt` (`Instrument.event`)

**Interfaces — Produces:**
- `InstrumentKind.EVENT`
- `enum class StrikeType { GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, BETWEEN, CUSTOM, NONE }`
- `enum class FeeModel { PROBABILITY }`
- `data class EventFee(model: FeeModel, takerRate: BigDecimal, makerRate: BigDecimal?, rounding: BigDecimal)`
- `data class EventTerms(series: String, event: String, strikeType: StrikeType, floor: BigDecimal?, cap: BigDecimal?,
  openAtMs: Long, closeAtMs: Long, occursAtMs: Long?, occasion: String?, canCloseEarly: Boolean, exclusive: Boolean,
  frequency: String?, title: String?, outcome: BigDecimal?, fee: EventFee?)`
- `Instrument.event: EventTerms? = null` (last parameter, so positional construction elsewhere is unchanged)

- [ ] Steps: add the types with KDoc; no behaviour, compiled by Task 2's test.

### Task 2: Event fields on the wire

**Files:**
- Create: `vgp-wire/src/main/kotlin/com/qkt/vgp/WireInstrument.kt` (move `WireInstrument` out of `Wire.kt`
  unchanged, then add fields)
- Modify: `vgp-wire/src/main/kotlin/com/qkt/vgp/Wire.kt` (remove the moved class)
- Modify: `host/src/main/kotlin/com/qkt/venuegateway/host/wire/WireReads.kt` (map `event`)
- Test: `host/src/test/kotlin/com/qkt/venuegateway/host/server/EventInstrumentWireTest.kt`

**Interfaces — Produces:** `WireInstrument` optional fields, in this order after `trading`: `series`, `event`,
`strike_type`, `floor`, `cap`, `open_at`, `close_at`, `occurs_at`, `occasion`, `can_close_early`, `exclusive`,
`frequency`, `title`, `outcome`, `fee_model`, `taker_fee_rate`, `maker_fee_rate`, `fee_rounding`. Enum values are
lowercase names (`greater_or_equal`, `probability`).

- [ ] **Step 1: failing test** `` `a ladder contract lists its series, event, strike, times and fee shape` ``:
  `KXINXU-26NOV06H1500-T7425`, kind `event`, currency `USD`, contract_size `1`, tick `0.01`, step `0.01`, min `1`,
  expiry 1794006000000, series `KXINXU`, event `KXINXU-26NOV06H1500`, strike `greater_or_equal`, floor `7425`,
  open 1794002400000, close 1794006000000, occasion `26NOV06H1500`, can_close_early false, exclusive false,
  frequency `1h`, title `7,425 or above`, fee `probability` 0.07 / 0.0175 / 0.01 → exact JSON string.
- [ ] **Step 2:** `` `a resolved-no contract writes its outcome as zero` `` → JSON contains `"outcome":"0"`.
- [ ] **Step 3:** `` `a head-to-head side with no strike lists custom with no bounds` `` → no `floor`/`cap` keys,
  `"strike_type":"custom"`, `"occurs_at":…`.
- [ ] **Step 4:** run `host` tests (CI) → FAIL (no fields); implement; → PASS. The existing
  `EquityInstrumentWireTest` stays green unchanged.
- [ ] **Step 5: commit** `feat(wire): list event contracts with their terms`.

### Task 3: The contract kit knows the event shape

**Files:**
- Create: `adapter-testkit/src/main/kotlin/com/qkt/venuegateway/testkit/EventChecks.kt`
- Modify: `adapter-testkit/src/main/kotlin/com/qkt/venuegateway/testkit/ContractChecks.kt` (expiry rule includes
  `EVENT`; call `EventChecks.terms(i)`)
- Test: `adapter-testkit/src/test/kotlin/com/qkt/venuegateway/testkit/EventChecksTest.kt` (add the test source set
  dependencies the module needs, matching `host`)

**Interfaces — Produces:** `internal object EventChecks { fun terms(i: Instrument) }` — event ⇔ `i.event != null`;
for events: expiry present, strike/right absent, `closeAtMs ≥ openAtMs`, `between` needs floor and cap with
floor < cap, `greater*` needs floor and no cap, `less*` needs cap and no floor, `custom`/`none` neither, `outcome`
within [0, 1] when present, fee rates within [0, 1) and rounding > 0 when present.

- [ ] Steps: one failing test per rule (named as behaviour, e.g. `` `a between contract without a cap is refused` ``),
  implement, pass, commit `feat(testkit): check the shape of event contracts`.

### Task 4: Docs

**Files:** `docs/vgp-v1-wire.md` (instrument section: kind list, the event fields table, keep-window note),
`docs/design.md` §3 kind list.

- [ ] Steps: edit, commit `docs(wire): describe the event instrument kind`.

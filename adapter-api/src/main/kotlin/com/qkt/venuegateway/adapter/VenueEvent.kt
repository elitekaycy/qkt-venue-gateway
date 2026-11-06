package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/**
 * How an event contract's question reads against its strike bounds: `GREATER` pays when the observed value is
 * above [EventTerms.floor], `BETWEEN` when it is within [EventTerms.floor] and [EventTerms.cap], `CUSTOM` when the
 * question is about something other than a number (a team, a candidate), `NONE` when it has no strike at all.
 */
enum class StrikeType { GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, BETWEEN, CUSTOM, NONE }

/** The shape of a venue's per-order fee. `PROBABILITY`: rate × contracts × p × (1 − p), rounded up to the unit. */
enum class FeeModel {
    PROBABILITY,
}

/**
 * The fee an event contract pays per order, by [model]: [takerRate] when the order takes liquidity, [makerRate] when
 * it rested (null: no maker fee), rounded up to [rounding]. The charges themselves still arrive on each fill.
 */
data class EventFee(
    val model: FeeModel,
    val takerRate: BigDecimal,
    val makerRate: BigDecimal?,
    val rounding: BigDecimal,
)

/**
 * What a binary event contract asks and when: it pays 1 per contract when its question resolves YES and 0 when NO.
 *
 * [series] is the recurring question (the family), [event] one instance of it (one close time, one game). The question
 * reads [strikeType] against [floor] and [cap]. Trading opens at [openAtMs] and stops by [closeAtMs] (earlier when
 * [canCloseEarly]); the instrument's `expiryMs` is when the result is expected. [occursAtMs] is when the thing asked
 * about happens (a game's start, a window's end). [occasion] is a key the venue shares across series about the same
 * occasion (one game's winner, spread and total). [exclusive] says the event's contracts partition its outcomes, so
 * exactly one resolves YES. [frequency] is the series' cadence, normalised (`15m`, `1h`, `1d`, `1w`, `1mo`, `1q`, `1y`,
 * `custom`, `one_off`). [title] is the contract's human label. [outcome] is the settled value once known, in [0, 1].
 */
data class EventTerms(
    val series: String,
    val event: String,
    val strikeType: StrikeType,
    val floor: BigDecimal?,
    val cap: BigDecimal?,
    val openAtMs: Long,
    val closeAtMs: Long,
    val occursAtMs: Long? = null,
    val occasion: String? = null,
    val canCloseEarly: Boolean = false,
    val exclusive: Boolean = false,
    val frequency: String? = null,
    val title: String? = null,
    val outcome: BigDecimal? = null,
    val fee: EventFee? = null,
)

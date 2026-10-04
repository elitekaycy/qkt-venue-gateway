package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/**
 * The optional services an adapter serves beyond orders, positions, the account and its fills. The host
 * reports them in `/v1/health` and never asks an adapter for one it does not declare.
 */
enum class Capability {
    /** Closed bars ([VenueAdapter.bars]). */
    BARS,

    /** Live quotes ([VenueAdapter.subscribeQuotes]). */
    QUOTES,

    /** Expiry settlements ([VenueAdapter.settlements]). */
    SETTLEMENTS,

    /** The funding the venue charged or credited the account ([VenueAdapter.funding]). */
    FUNDING,

    /** The venue's public funding-rate history of its perpetuals ([VenueAdapter.fundingRates]). */
    FUNDING_RATES,

    /** The history of a contract's mark and index prices, sampled per window ([VenueAdapter.marks]). */
    MARK_PRICES,
}

/**
 * Funding the venue charged ([amount] positive) or credited ([amount] negative) the account on perpetual
 * [symbol] at [timeMs], in [currency]. [position] is the account's signed quantity the venue charged on,
 * null when the venue does not say. [fundingId] is the venue's own record id, so a record seen twice is one.
 */
data class VenueFunding(
    val fundingId: String,
    val symbol: String,
    val amount: BigDecimal,
    val currency: String,
    val position: BigDecimal?,
    val timeMs: Long,
)

/**
 * One published funding rate of a perpetual: a unit long held through [timeMs] pays `rate × price` per unit
 * of the underlying (a negative [rate] pays the short). [price] is the price the venue applied, null when
 * it publishes none.
 */
data class VenueFundingRate(
    val timeMs: Long,
    val rate: BigDecimal,
    val price: BigDecimal?,
)

/** The adapter does not serve what was asked (a capability it does not declare). Served as `501 unsupported`. */
class VenueUnsupportedException(
    what: String,
) : RuntimeException("the adapter does not serve $what")

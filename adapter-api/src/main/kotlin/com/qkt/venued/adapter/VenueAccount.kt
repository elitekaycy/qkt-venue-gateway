package com.qkt.venued.adapter

import java.math.BigDecimal

/** Who the adapter is logged in as: the account [login], live or test ([mode]), and its [currency]. */
data class VenueIdentity(
    val login: String,
    val mode: TradeMode,
    val currency: String,
)

/**
 * One instrument the venue lists, by its venue [code]. [expiryMs] only for dated contracts; [strike],
 * [right] (`call`/`put`) and [underlying] only for options.
 */
data class Instrument(
    val code: String,
    val kind: InstrumentKind,
    val currency: String,
    val contractSize: BigDecimal,
    val tickSize: BigDecimal,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val expiryMs: Long? = null,
    val strike: BigDecimal? = null,
    val right: String? = null,
    val underlying: String? = null,
)

/** The account's money in [currency]: what it holds, what it is worth, and its margin. */
data class AccountSnapshot(
    val currency: String,
    val balance: BigDecimal,
    val equity: BigDecimal,
    val marginUsed: BigDecimal,
    val marginAvailable: BigDecimal,
    val initialMargin: BigDecimal? = null,
    val maintenanceMargin: BigDecimal? = null,
)

/** One position, signed (+long, −short): per symbol when netting, per [ticket] when hedging. */
data class PositionRow(
    val symbol: String,
    val quantity: BigDecimal,
    val avgPrice: BigDecimal,
    val ticket: String? = null,
    val openedAtMs: Long? = null,
)

/** Every open position and how the venue holds them. */
data class Positions(
    val accounting: Accounting,
    val rows: List<PositionRow>,
)

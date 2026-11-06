package com.qkt.venuegateway.kalshi.client

import java.math.BigDecimal

/**
 * An order in Kalshi's V2 words: [bookSide] `bid` buys YES, `ask` sells YES (buys NO); [yesPrice] is its YES limit.
 * Fill costs are what the account paid for the side it bought: YES on a bid, NO on an ask.
 */
data class KalshiOrder(
    val orderId: String,
    val clientOrderId: String?,
    val ticker: String,
    val bookSide: String,
    val status: String,
    val yesPrice: BigDecimal,
    val fillCount: BigDecimal,
    val remainingCount: BigDecimal,
    val initialCount: BigDecimal,
    val fillCost: BigDecimal,
    val createdMs: Long,
    val updatedMs: Long,
)

/** One execution of the account's order [orderId]; [clientOrderId] only when Kalshi sent it (pushes carry it). */
data class KalshiFill(
    val fillId: String,
    val orderId: String,
    val clientOrderId: String?,
    val ticker: String,
    val bookSide: String,
    val count: BigDecimal,
    val yesPrice: BigDecimal,
    val fee: BigDecimal,
    val timeMs: Long,
)

/** A market the account held settling: [marketResult] `yes`, `no` or `scalar` (then [valueCents] a YES pays). */
data class KalshiSettlement(
    val ticker: String,
    val marketResult: String,
    val valueCents: Int?,
    val settledMs: Long,
)

/** The account's position in [ticker]: [position] YES contracts (negative: NO), costing [exposure] in dollars. */
data class KalshiPosition(
    val ticker: String,
    val position: BigDecimal,
    val exposure: BigDecimal,
    val exchangeIndex: Int?,
)

/** The account's cash per shard (exchange index) in dollars, and what its positions are worth in cents. */
data class KalshiBalance(
    val byShard: Map<Int, BigDecimal>,
    val portfolioValueCents: Long,
)

/** Kalshi's answer to a placed order: its id and what filled at once ([fillCount]) or still rests. */
data class KalshiPlaced(
    val orderId: String,
    val fillCount: BigDecimal,
    val remainingCount: BigDecimal,
    val timeMs: Long,
)

/**
 * A V2 order to send: [side] `bid` or `ask` on the YES book, [count] contracts at the YES [price] in dollars,
 * [timeInForce] `good_till_canceled`, `immediate_or_cancel` or `fill_or_kill`, labelled [clientOrderId].
 */
data class KalshiNewOrder(
    val ticker: String,
    val side: String,
    val count: BigDecimal,
    val price: BigDecimal,
    val timeInForce: String,
    val clientOrderId: String,
    val reduceOnly: Boolean,
)

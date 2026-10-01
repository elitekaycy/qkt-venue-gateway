package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal

/**
 * An order as Deribit reports it, in its own words: [direction] `buy`/`sell`; [orderType] `limit`,
 * `market`, `stop_market`, `stop_limit`; [state] `open`, `untriggered`, `filled`, `cancelled`,
 * `rejected`. [price] is null when Deribit sends a word (`market_price`) instead of a number; a market
 * order's number is Deribit's protection cap, not a limit. [filledAmount] is zero when absent (an
 * untriggered stop has none). A stop that fires becomes a new order with a new [orderId], the same
 * [label] and [triggered] true.
 */
data class DeribitOrder(
    val orderId: String,
    val label: String?,
    val instrument: String,
    val direction: String,
    val orderType: String,
    val state: String,
    val amount: BigDecimal,
    val filledAmount: BigDecimal,
    val price: BigDecimal?,
    val averagePrice: BigDecimal?,
    val triggerPrice: BigDecimal?,
    val triggered: Boolean?,
    val timeInForce: String,
    val reduceOnly: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
    val cancelReason: String?,
)

/** One execution, by Deribit's own [tradeId]; [fee] is positive when charged, negative when rebated. */
data class DeribitTrade(
    val tradeId: String,
    val orderId: String,
    val label: String?,
    val instrument: String,
    val direction: String,
    val amount: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal,
    val feeCurrency: String,
    val timestampMs: Long,
)

/**
 * A position as Deribit reports it. Both size fields are kept as sent because their meaning depends on
 * the instrument: a linear future's [size] is its dollar value and [sizeCurrency] its coin amount; an
 * option's [size] is its coin amount and [sizeCurrency] is absent. [direction] is `buy`, `sell` or `zero`.
 */
data class DeribitPosition(
    val instrument: String,
    val kind: String,
    val direction: String,
    val size: BigDecimal,
    val sizeCurrency: BigDecimal?,
    val averagePrice: BigDecimal,
)

/** The account's money in one [currency], and the account's own [accountId]. */
data class DeribitAccount(
    val accountId: String,
    val currency: String,
    val balance: BigDecimal,
    val equity: BigDecimal,
    val initialMargin: BigDecimal,
    val maintenanceMargin: BigDecimal,
    val availableFunds: BigDecimal,
)

/** One page of a history: its [items], and whether [more] follow. */
data class DeribitPage<T>(
    val items: List<T>,
    val more: Boolean,
)

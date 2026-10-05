package com.qkt.venuegateway.bybit.client

import java.math.BigDecimal

/**
 * One order as Bybit reports it (`/v5/order/realtime`, `/v5/order/history`, the private `order` topic).
 * [orderLinkId] is the client's id, null on an order placed without one. [orderType] is `Market` or `Limit`;
 * a conditional order carries its [triggerPrice] and a [stopOrderType] (`Stop`). [price] on a market order is
 * Bybit's protection price, not a limit. [cumExecQty] is cumulative; [avgPrice] is null until something filled.
 */
data class BybitOrder(
    val orderId: String,
    val orderLinkId: String?,
    val symbol: String,
    val side: String,
    val orderType: String,
    val qty: BigDecimal,
    val price: BigDecimal?,
    val triggerPrice: BigDecimal?,
    val stopOrderType: String?,
    val timeInForce: String,
    val orderStatus: String,
    val cumExecQty: BigDecimal,
    val avgPrice: BigDecimal?,
    val reduceOnly: Boolean,
    val rejectReason: String?,
    val positionIdx: Int,
    val createdMs: Long,
    val updatedMs: Long,
)

/**
 * One execution (`/v5/execution/list`, the private `execution` topic). [execType] says what it is: `Trade` an
 * order filling, `Funding` a perpetual's funding settled on a held position (its [execFee] positive when the
 * account paid, [execQty] the position and [side] its direction), and `BustTrade`, `AdlTrade`, `Delivery`,
 * `Settle` moves made by Bybit itself; null when the record names none. [leavesQty] is what the order still
 * had to fill after this execution.
 */
data class BybitExecution(
    val execId: String,
    val orderId: String,
    val orderLinkId: String?,
    val symbol: String,
    val side: String,
    val execType: String?,
    val execQty: BigDecimal,
    val execPrice: BigDecimal,
    val execFee: BigDecimal,
    val feeCurrency: String?,
    val execTimeMs: Long,
    val orderQty: BigDecimal?,
    val leavesQty: BigDecimal?,
)

/**
 * One position slot (`/v5/position/list`): [size] unsigned with its [side] (`Buy` long, `Sell` short, empty
 * when flat). [positionIdx] is 0 in one-way mode, 1 (long) or 2 (short) in hedge mode.
 */
data class BybitPosition(
    val symbol: String,
    val side: String?,
    val size: BigDecimal,
    val avgPrice: BigDecimal?,
    val positionIdx: Int,
    val createdMs: Long?,
)

/**
 * A unified account's money (`/v5/account/wallet-balance`): [coin]'s own wallet balance and equity, and the
 * account's margin, which a unified account pools across its coins and values in USD: the initial and
 * maintenance margin in use and what is available to open more.
 */
data class BybitWallet(
    val coin: String,
    val walletBalance: BigDecimal,
    val equity: BigDecimal,
    val initialMargin: BigDecimal,
    val maintenanceMargin: BigDecimal,
    val available: BigDecimal,
)

/**
 * An order to send (`/v5/order/create`), in Bybit's words; null fields are not sent. A spot conditional order
 * names its [orderFilter] (`StopOrder`); a contract's names its [triggerDirection] (1 fires as the price rises
 * to the trigger, 2 as it falls) and the price it watches ([triggerBy]).
 */
data class BybitNewOrder(
    val category: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val qty: BigDecimal,
    val price: BigDecimal?,
    val triggerPrice: BigDecimal?,
    val triggerDirection: Int?,
    val triggerBy: String?,
    val timeInForce: String,
    val orderLinkId: String,
    val reduceOnly: Boolean,
    val positionIdx: Int?,
    val orderFilter: String? = null,
)

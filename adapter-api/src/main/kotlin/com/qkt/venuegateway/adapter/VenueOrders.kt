package com.qkt.venuegateway.adapter

import java.math.BigDecimal

/** An order to place, carrying the client's id, which the adapter sends as the venue order label. */
data class NewOrder(
    val clientOrderId: String,
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
    val timeInForce: TimeInForce,
    val reduceOnly: Boolean = false,
)

/** A change to a working order; null fields stay as they are. */
data class OrderChange(
    val quantity: BigDecimal? = null,
    val limitPrice: BigDecimal? = null,
    val stopPrice: BigDecimal? = null,
)

/** An order as the venue reports it; [filledQuantity] is cumulative. */
data class VenueOrder(
    val clientOrderId: String,
    val venueOrderId: String?,
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal?,
    val stopPrice: BigDecimal?,
    val timeInForce: TimeInForce,
    val reduceOnly: Boolean,
    val status: OrderStatus,
    val filledQuantity: BigDecimal,
    val avgFillPrice: BigDecimal?,
    val rejectReason: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** One typed charge in [currency]. */
data class Cost(
    val kind: CostKind,
    val amount: BigDecimal,
    val currency: String,
)

/** One execution, identified by the venue's own [fillId] so a fill seen twice is one fill. */
data class VenueFill(
    val clientOrderId: String,
    val venueOrderId: String?,
    val fillId: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val timeMs: Long,
    val costs: List<Cost> = emptyList(),
)

/** Expiring [symbol] settled at [price] per unit, with what the venue charged the account. */
data class VenueSettlement(
    val symbol: String,
    val price: BigDecimal,
    val timeMs: Long,
    val costs: List<Cost> = emptyList(),
)

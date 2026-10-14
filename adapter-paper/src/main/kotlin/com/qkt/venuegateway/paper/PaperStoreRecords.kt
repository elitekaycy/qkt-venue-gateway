package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import java.math.BigDecimal
import kotlinx.serialization.Serializable

// The records of `paper.json` ([PaperStore]), decimals kept as their exact text.

@Serializable
internal data class Position(
    val symbol: String,
    val quantity: String,
    val avgPrice: String,
)

@Serializable
internal data class Settlement(
    val symbol: String,
    val price: String,
    val timeMs: Long,
)

@Serializable
internal data class Order(
    val label: String,
    val venueId: String?,
    val symbol: String,
    val side: String,
    val type: String,
    val quantity: String,
    val limit: String?,
    val stop: String?,
    val tif: String,
    val reduceOnly: Boolean,
    val status: String,
    val filled: String,
    val avgFill: String?,
    val reason: String?,
    val createdMs: Long,
    val updatedMs: Long,
) {
    constructor(o: VenueOrder) : this(
        o.clientOrderId,
        o.venueOrderId,
        o.symbol,
        o.side.name,
        o.type.name,
        o.quantity.toPlainString(),
        o.limitPrice?.toPlainString(),
        o.stopPrice?.toPlainString(),
        o.timeInForce.name,
        o.reduceOnly,
        o.status.name,
        o.filledQuantity.toPlainString(),
        o.avgFillPrice?.toPlainString(),
        o.rejectReason,
        o.createdAtMs,
        o.updatedAtMs,
    )

    fun order() =
        VenueOrder(
            label,
            venueId,
            symbol,
            Side.valueOf(side),
            OrderType.valueOf(type),
            BigDecimal(quantity),
            limit?.let(::BigDecimal),
            stop?.let(::BigDecimal),
            TimeInForce.valueOf(tif),
            reduceOnly,
            OrderStatus.valueOf(status),
            BigDecimal(filled),
            avgFill?.let(::BigDecimal),
            reason,
            createdMs,
            updatedMs,
        )
}

@Serializable
internal data class Fill(
    val label: String,
    val venueId: String?,
    val fillId: String,
    val symbol: String,
    val side: String,
    val quantity: String,
    val price: String,
    val timeMs: Long,
    val costs: List<Charge>,
) {
    constructor(f: VenueFill) : this(
        f.clientOrderId,
        f.venueOrderId,
        f.fillId,
        f.symbol,
        f.side.name,
        f.quantity.toPlainString(),
        f.price.toPlainString(),
        f.timeMs,
        f.costs.map { Charge(it.kind.name, it.amount.toPlainString(), it.currency) },
    )

    fun fill() =
        VenueFill(
            label,
            venueId,
            fillId,
            symbol,
            Side.valueOf(side),
            BigDecimal(quantity),
            BigDecimal(price),
            timeMs,
            costs.map { Cost(CostKind.valueOf(it.kind), BigDecimal(it.amount), it.currency) },
        )
}

@Serializable
internal data class Charge(
    val kind: String,
    val amount: String,
    val currency: String,
)

@Serializable
internal data class FundingRecord(
    val id: String,
    val symbol: String,
    val amount: String,
    val currency: String,
    val position: String?,
    val timeMs: Long,
) {
    constructor(f: VenueFunding) : this(
        f.fundingId,
        f.symbol,
        f.amount.toPlainString(),
        f.currency,
        f.position?.toPlainString(),
        f.timeMs,
    )

    fun funding() = VenueFunding(id, symbol, BigDecimal(amount), currency, position?.let(::BigDecimal), timeMs)
}

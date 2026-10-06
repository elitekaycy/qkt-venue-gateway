package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.bybit.BybitMarketMapping.side
import com.qkt.venuegateway.bybit.client.BybitExecution
import com.qkt.venuegateway.bybit.client.BybitOrder
import com.qkt.venuegateway.bybit.client.BybitPosition
import com.qkt.venuegateway.bybit.client.BybitWallet

/**
 * Where Bybit's account words become the gateway's types (orders sent are [BybitOrderMapping]; the market is
 * [BybitMarketMapping]). Orders and executions without an `orderLinkId` were not sent through the gateway and
 * map to null. [currency] names a cost or funding whose record leaves its coin blank. A Bybit value this
 * mapping does not know fails by name rather than being guessed.
 */
internal object BybitMapping {
    /** [o] as the order the client sent: a conditional order is a stop whether or not it has fired. */
    fun order(o: BybitOrder): VenueOrder? {
        val label = o.orderLinkId ?: return null
        val type = type(o)
        return VenueOrder(
            clientOrderId = label,
            venueOrderId = o.orderId,
            symbol = o.symbol,
            side = side(o.side),
            type = type,
            quantity = o.qty,
            limitPrice = o.price.takeIf { type == OrderType.LIMIT || type == OrderType.STOP_LIMIT },
            stopPrice = o.triggerPrice.takeIf { type == OrderType.STOP || type == OrderType.STOP_LIMIT },
            timeInForce = timeInForce(o.timeInForce),
            reduceOnly = o.reduceOnly,
            status = status(o.orderStatus),
            filledQuantity = o.cumExecQty,
            avgFillPrice = o.avgPrice.takeIf { o.cumExecQty.signum() > 0 },
            rejectReason = (o.rejectReason ?: "rejected").takeIf { o.orderStatus == "Rejected" },
            createdAtMs = o.createdMs,
            updatedAtMs = o.updatedMs,
        )
    }

    /**
     * [e] as a fill when it is an order of the gateway's filling: an execution of type `Trade`, or one naming no
     * type (dropping a real fill is the worse mistake). Its fee is a commission, positive charged, negative a
     * rebate; a zero fee is omitted.
     */
    fun fill(
        e: BybitExecution,
        currency: String,
    ): VenueFill? {
        val label = e.orderLinkId ?: return null
        if (e.execType != null && e.execType != "Trade") return null
        val costs =
            if (e.execFee.signum() ==
                0
            ) {
                emptyList()
            } else {
                listOf(Cost(CostKind.COMMISSION, e.execFee, e.feeCurrency ?: currency))
            }
        return VenueFill(
            label,
            e.orderId,
            e.execId,
            e.symbol,
            side(e.side),
            e.execQty,
            e.execPrice,
            e.execTimeMs,
            costs,
        )
    }

    /**
     * [e] as funding when it is a `Funding` execution: its `execFee` is the charge, positive when the account
     * paid (Bybit's transaction log documents its `funding` as the opposite of this fee), on the position
     * `execQty` held on `side`.
     */
    fun funding(
        e: BybitExecution,
        currency: String,
    ): VenueFunding? {
        if (e.execType != "Funding") return null
        val position = if (side(e.side) == Side.SELL) e.execQty.negate() else e.execQty
        return VenueFunding(e.execId, e.symbol, e.execFee, e.feeCurrency ?: currency, position, e.execTimeMs)
    }

    /** The account in [w]'s coin: that coin's balance and equity, and the unified account's pooled margin (in USD). */
    fun account(w: BybitWallet) =
        AccountSnapshot(
            w.coin,
            w.walletBalance,
            w.equity,
            w.initialMargin,
            w.available,
            w.initialMargin,
            w.maintenanceMargin,
        )

    /**
     * [p] as a signed position, or null when flat; in hedge mode each side is its own ticket (`long`, `short`). Its
     * open time is not reported: Bybit's `createdTime` is the slot's, kept when a later position reopens it
     * (measured on testnet 2026-10-06), not when the position held now was opened.
     */
    fun position(p: BybitPosition): PositionRow? {
        if (p.size.signum() == 0) return null
        val side = side(p.side ?: error("bybit position ${p.symbol} of size ${p.size} has no side"))
        val signed = if (side == Side.SELL) p.size.negate() else p.size
        val ticket =
            when (p.positionIdx) {
                0 -> null
                1 -> "long"
                2 -> "short"
                else -> error("bybit position index ${p.positionIdx} is not supported")
            }
        val average = p.avgPrice ?: error("bybit position ${p.symbol} has no average price")
        return PositionRow(p.symbol, signed, average, ticket)
    }

    private fun type(o: BybitOrder): OrderType {
        val conditional = o.triggerPrice != null || o.stopOrderType != null
        return when (o.orderType) {
            "Market" -> if (conditional) OrderType.STOP else OrderType.MARKET
            "Limit" -> if (conditional) OrderType.STOP_LIMIT else OrderType.LIMIT
            else -> error("bybit order type ${o.orderType} is not supported")
        }
    }

    private fun status(state: String): OrderStatus =
        when (state) {
            "Created", "New", "PartiallyFilled", "Untriggered", "Triggered", "Active" -> OrderStatus.WORKING
            "Filled" -> OrderStatus.FILLED
            "Cancelled", "PartiallyFilledCanceled", "Deactivated" -> OrderStatus.CANCELLED
            "Rejected" -> OrderStatus.REJECTED
            else -> error("bybit order status $state is not supported")
        }

    private fun timeInForce(bybit: String): TimeInForce =
        when (bybit) {
            "GTC", "PostOnly" -> TimeInForce.GTC
            "IOC" -> TimeInForce.IOC
            "FOK" -> TimeInForce.FOK
            else -> error("bybit time in force $bybit is not supported")
        }
}

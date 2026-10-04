package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.deribit.client.DeribitNewOrder
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPosition
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.DeribitTransaction

/**
 * Where Deribit's account words become the gateway's neutral types (the market side is
 * [DeribitMarketMapping]), for linear contracts, whose amounts are in the base coin and so are
 * quantities as they are. Orders and trades without a label were not sent through the gateway and map
 * to null. A Deribit value this mapping does not know fails by name rather than being guessed.
 */
object DeribitMapping {
    /** [o] as the order the client sent: a stop that fired is reported as the stop it was. */
    fun order(o: DeribitOrder): VenueOrder? {
        val label = o.label ?: return null
        val type = type(o)
        val filled = o.filledAmount.signum() > 0
        return VenueOrder(
            clientOrderId = label,
            venueOrderId = o.orderId,
            symbol = o.instrument,
            side = side(o.direction),
            type = type,
            quantity = o.amount,
            limitPrice = o.price.takeIf { type == OrderType.LIMIT || type == OrderType.STOP_LIMIT },
            stopPrice = o.triggerPrice.takeIf { type == OrderType.STOP || type == OrderType.STOP_LIMIT },
            timeInForce = timeInForce(o.timeInForce),
            reduceOnly = o.reduceOnly,
            status = status(o.state),
            filledQuantity = o.filledAmount,
            avgFillPrice = o.averagePrice.takeIf { filled },
            rejectReason = o.cancelReason.takeIf { o.state == "rejected" },
            createdAtMs = o.createdMs,
            updatedAtMs = o.updatedMs,
        )
    }

    /**
     * The funding log row [t] realized on a perpetual, or null when it realized none or is not about one
     * ([isPerpetual] says). Deribit's `interest_pl` is the account's gain, so the charge is its negation. A
     * `settlement` row's position is the one it charged on; another row's position is after its trade,
     * not the one charged on, so it is not reported.
     */
    fun funding(
        t: DeribitTransaction,
        isPerpetual: (String) -> Boolean,
    ): VenueFunding? {
        val instrument = t.instrument ?: return null
        val gain = t.interestPl?.takeIf { it.signum() != 0 } ?: return null
        if (!isPerpetual(instrument)) return null
        val position = t.position.takeIf { t.type == "settlement" }
        return VenueFunding("tx-${t.id}", instrument, gain.negate(), t.currency, position, t.timestampMs)
    }

    /** [t] as a fill, its fee a commission (positive charged, negative rebated); zero fees are omitted. */
    fun fill(t: DeribitTrade): VenueFill? {
        val label = t.label ?: return null
        val costs = if (t.fee.signum() == 0) emptyList() else listOf(Cost(CostKind.COMMISSION, t.fee, t.feeCurrency))
        return VenueFill(
            label,
            t.orderId,
            t.tradeId,
            t.instrument,
            side(t.direction),
            t.amount,
            t.price,
            t.timestampMs,
            costs,
        )
    }

    /**
     * [p] as a signed position, or null when flat. A future's quantity is its `size_currency` (its
     * `size` is dollars); an option's is its `size`.
     */
    fun position(p: DeribitPosition): PositionRow? {
        val amount =
            when (p.kind) {
                "option" -> p.size
                "future" -> p.sizeCurrency ?: error("deribit future ${p.instrument} has no size_currency")
                else -> error("deribit position kind ${p.kind} is not supported")
            }
        if (amount.signum() == 0) return null
        val signed = if (p.direction == "sell") amount.abs().negate() else amount.abs()
        return PositionRow(p.instrument, signed, p.averagePrice)
    }

    /** [o] in Deribit's words, a stop triggering on [trigger] (`last_price`, `mark_price`, `index_price`). */
    fun newOrder(
        o: NewOrder,
        trigger: String,
    ): DeribitNewOrder {
        val stop = o.type == OrderType.STOP || o.type == OrderType.STOP_LIMIT
        val limited = o.type == OrderType.LIMIT || o.type == OrderType.STOP_LIMIT
        return DeribitNewOrder(
            label = o.clientOrderId,
            instrument = o.symbol,
            direction = if (o.side == Side.BUY) "buy" else "sell",
            type =
                when (o.type) {
                    OrderType.MARKET -> "market"
                    OrderType.LIMIT -> "limit"
                    OrderType.STOP -> "stop_market"
                    OrderType.STOP_LIMIT -> "stop_limit"
                },
            amount = o.quantity,
            price = o.limitPrice.takeIf { limited },
            triggerPrice = o.stopPrice.takeIf { stop },
            trigger = trigger.takeIf { stop },
            timeInForce = TIME_IN_FORCE.getValue(o.timeInForce),
            reduceOnly = o.reduceOnly,
        )
    }

    private fun type(o: DeribitOrder): OrderType =
        when (o.orderType) {
            "limit" -> if (o.triggered == true) OrderType.STOP_LIMIT else OrderType.LIMIT
            "market" -> if (o.triggered == true) OrderType.STOP else OrderType.MARKET
            "stop_market" -> OrderType.STOP
            "stop_limit" -> OrderType.STOP_LIMIT
            else -> error("deribit order type ${o.orderType} is not supported")
        }

    private fun status(state: String): OrderStatus =
        when (state) {
            "open", "untriggered" -> OrderStatus.WORKING
            "filled" -> OrderStatus.FILLED
            "cancelled" -> OrderStatus.CANCELLED
            "rejected" -> OrderStatus.REJECTED
            else -> error("deribit order state $state is not supported")
        }

    private fun side(direction: String): Side =
        when (direction) {
            "buy" -> Side.BUY
            "sell" -> Side.SELL
            else -> error("deribit direction $direction is not supported")
        }

    private fun timeInForce(deribit: String): TimeInForce =
        TIME_IN_FORCE.entries.firstOrNull { it.value == deribit }?.key
            ?: error("deribit time in force $deribit is not supported")

    private val TIME_IN_FORCE =
        mapOf(
            TimeInForce.GTC to "good_til_cancelled",
            TimeInForce.IOC to "immediate_or_cancel",
            TimeInForce.FOK to "fill_or_kill",
            TimeInForce.DAY to "good_til_day",
        )
}

package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.kalshi.client.KalshiMarket
import com.qkt.venuegateway.kalshi.client.KalshiNewOrder
import com.qkt.venuegateway.kalshi.client.KalshiOrder
import java.math.BigDecimal
import java.math.RoundingMode

/** What the gateway sent under a label, which Kalshi's order does not echo: the type, time in force and reduce-only. */
internal data class KalshiSent(
    val type: OrderType,
    val timeInForce: TimeInForce,
    val reduceOnly: Boolean,
)

/**
 * Orders between VGP and Kalshi's V2 book. A quantity is signed YES: a buy is a `bid` (buys YES), a sell an `ask`
 * (sells YES, buys NO). Kalshi has no market order: one is sent as an immediate-or-cancel limit at the worst price
 * on the market's grid (its top for a buy, its bottom for a sell), so it takes what the book holds and never
 * rests; a market order's GTC (as `POST /v1/positions/close` sends) is read as IOC. Kalshi has no stop and no day
 * order: both are refused. Prices take at most 4 decimals and quantities 2, as Kalshi does.
 */
internal object KalshiOrderMapping {
    fun newOrder(
        o: NewOrder,
        market: KalshiMarket,
        reduceOnly: Boolean,
    ): KalshiNewOrder {
        if (o.type == OrderType.STOP || o.type == OrderType.STOP_LIMIT) {
            throw VenueRefusedException("kalshi has no stop orders: ${o.type.name.lowercase()} is not sent")
        }
        if (o.timeInForce == TimeInForce.DAY) {
            throw VenueRefusedException("kalshi has no day orders: send gtc, ioc or fok")
        }
        val atMarket = o.type == OrderType.MARKET
        val price =
            if (atMarket) worst(o.side, market) else o.limitPrice ?: throw VenueRefusedException("a limit needs one")
        return KalshiNewOrder(
            ticker = o.symbol,
            side = if (o.side == Side.BUY) "bid" else "ask",
            count = scaled(o.quantity, COUNT_DECIMALS, 0, "quantity"),
            price = scaled(price, PRICE_DECIMALS, 2, "price"),
            timeInForce = timeInForce(o.timeInForce, atMarket),
            clientOrderId = o.clientOrderId,
            reduceOnly = reduceOnly,
        )
    }

    /** Whether Kalshi takes `reduce_only` on [o] as sent: only immediate-or-cancel (a market order is). */
    fun immediate(o: NewOrder) = o.type == OrderType.MARKET || o.timeInForce == TimeInForce.IOC

    /** [o] as a VGP order, with what was [sent] under its label when the gateway sent it. */
    fun order(
        o: KalshiOrder,
        sent: KalshiSent?,
    ): VenueOrder {
        val type = sent?.type ?: OrderType.LIMIT
        val buy = o.bookSide == BID
        return VenueOrder(
            clientOrderId = o.clientOrderId ?: o.orderId,
            venueOrderId = o.orderId,
            symbol = o.ticker,
            side = if (buy) Side.BUY else Side.SELL,
            type = type,
            quantity = o.initialCount.max(o.fillCount + o.remainingCount),
            limitPrice = o.yesPrice.takeIf { type != OrderType.MARKET },
            stopPrice = null,
            timeInForce = sent?.timeInForce ?: TimeInForce.GTC,
            reduceOnly = sent?.reduceOnly ?: false,
            status = status(o),
            filledQuantity = o.fillCount,
            avgFillPrice = averageYes(o.fillCost, o.fillCount, buy),
            rejectReason = null,
            createdAtMs = o.createdMs,
            updatedAtMs = o.updatedMs,
        )
    }

    fun status(o: KalshiOrder): OrderStatus =
        when (o.status) {
            "resting" -> OrderStatus.WORKING
            "executed" -> OrderStatus.FILLED
            "canceled" -> OrderStatus.CANCELLED
            else -> error("kalshi order status ${o.status} of ${o.orderId} is not known")
        }

    /** The YES price paid on average: a bid's cost buys YES, an ask's buys NO at 1 − p; null before any fill. */
    fun averageYes(
        cost: BigDecimal,
        count: BigDecimal,
        bid: Boolean,
    ): BigDecimal? {
        if (count.signum() == 0) return null
        val each = cost.divide(count, AVERAGE_DECIMALS, RoundingMode.HALF_EVEN)
        return (if (bid) each else BigDecimal.ONE - each).stripTrailingZeros()
    }

    /** The worst price [market]'s grid takes on [side]: a step under its top for a buy, over its bottom for a sell. */
    fun worst(
        side: Side,
        market: KalshiMarket,
    ): BigDecimal {
        val ranges = market.priceRanges.sortedBy { it.start }
        if (ranges.isEmpty()) return if (side == Side.BUY) BigDecimal("0.99") else BigDecimal("0.01")
        val (top, bottom) = ranges.last() to ranges.first()
        return if (side == Side.BUY) top.end - top.step else bottom.start + bottom.step
    }

    private fun timeInForce(
        tif: TimeInForce,
        market: Boolean,
    ): String =
        when {
            tif == TimeInForce.FOK -> "fill_or_kill"
            market || tif == TimeInForce.IOC -> "immediate_or_cancel"
            else -> "good_till_canceled"
        }

    private fun scaled(
        value: BigDecimal,
        decimals: Int,
        least: Int,
        what: String,
    ): BigDecimal {
        val plain = value.stripTrailingZeros()
        if (plain.scale() > decimals) {
            throw VenueRefusedException("kalshi takes a $what of at most $decimals decimals: $value")
        }
        return plain.setScale(maxOf(least, plain.scale()))
    }

    const val BID = "bid"
    private const val PRICE_DECIMALS = 4
    private const val COUNT_DECIMALS = 2
    private const val AVERAGE_DECIMALS = 6
}

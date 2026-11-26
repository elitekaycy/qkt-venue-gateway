package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import java.math.BigDecimal

/** What Alpaca refuses in an order's quantity, checked before it is sent so the refusal names the rule. */
internal object AlpacaOrderRules {
    /**
     * The quantity [order] is sent for, once Alpaca's rules allow it: a coin takes `stop_limit` but no plain `stop`
     * (measured: 422 `invalid order type for crypto order`), a reduce-only order is cut to the position [held], and a
     * fractional share quantity is checked.
     */
    fun quantity(
        order: NewOrder,
        listed: AlpacaListed,
        held: () -> BigDecimal,
    ): BigDecimal {
        if (listed.crypto && order.type == OrderType.STOP) {
            throw VenueRefusedException("alpaca takes no stop order on a coin, only stop_limit: ${order.symbol}")
        }
        val quantity = if (order.reduceOnly) reducible(order, held()) else order.quantity
        if (!listed.crypto && quantity.stripTrailingZeros().scale() > 0) fractionAllowed(order, listed)
        return quantity
    }

    /** Alpaca's rules for a fractional share quantity (recorded: whole-share-only assets, GTC and short sales refused). */
    fun fractionAllowed(
        order: NewOrder,
        listed: AlpacaListed,
    ) {
        if (listed.instrument.volumeStep >= BigDecimal.ONE) {
            throw VenueRefusedException(
                "alpaca lists ${order.symbol} in whole shares: ${order.quantity.toPlainString()}",
            )
        }
        if (order.timeInForce != TimeInForce.DAY) {
            throw VenueRefusedException(
                "alpaca takes a fractional quantity on DAY orders only, not ${order.timeInForce}",
            )
        }
    }

    /**
     * Alpaca has no reduce-only flag: a reduce-only [order] is sent for at most the position [held] it reduces, and
     * refused when there is nothing to reduce.
     */
    fun reducible(
        order: NewOrder,
        held: BigDecimal,
    ): BigDecimal {
        val reducing = if (order.side == Side.SELL) held.signum() > 0 else held.signum() < 0
        if (!reducing) {
            throw VenueRefusedException(
                "reduce-only ${order.side} of ${order.symbol} with no position to reduce",
            )
        }
        return order.quantity.min(held.abs())
    }
}

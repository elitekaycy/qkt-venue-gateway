package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BybitNewOrder

/**
 * An order the client sent, in Bybit's words. The client's id travels as `orderLinkId`, which Bybit caps at
 * [MAX_LABEL] characters; a longer one, and a day order (Bybit has no day time in force), are refused here
 * rather than sent. A stop is a conditional order: on a contract it fires on [trigger] as the price rises to it
 * (a buy) or falls to it (a sell); on spot it is a `StopOrder` firing on the last price. A spot market order's
 * quantity is in the coin bought or sold (`marketUnit` `baseCoin`), as every quantity of the gateway is.
 */
internal object BybitOrderMapping {
    /** The longest `orderLinkId` Bybit takes (measured on testnet: "order link id is longer than 45"). */
    const val MAX_LABEL = 45

    fun newOrder(
        o: NewOrder,
        settings: BybitSettings,
        positionIdx: Int?,
    ): BybitNewOrder {
        if (o.clientOrderId.length > MAX_LABEL) {
            throw VenueRefusedException(
                "bybit takes client order ids of at most $MAX_LABEL characters: ${o.clientOrderId}",
            )
        }
        if (o.timeInForce ==
            TimeInForce.DAY
        ) {
            throw VenueRefusedException("bybit has no day orders: send gtc, ioc or fok")
        }
        val stop = o.type == OrderType.STOP || o.type == OrderType.STOP_LIMIT
        val limited = o.type == OrderType.LIMIT || o.type == OrderType.STOP_LIMIT
        val contracts = settings.contracts
        return BybitNewOrder(
            category = settings.category,
            symbol = o.symbol,
            side = if (o.side == Side.BUY) "Buy" else "Sell",
            orderType = if (limited) "Limit" else "Market",
            qty = o.quantity,
            price = o.limitPrice.takeIf { limited },
            triggerPrice = o.stopPrice.takeIf { stop },
            triggerDirection = (if (o.side == Side.BUY) RISES else FALLS).takeIf { stop && contracts },
            triggerBy = settings.stopTrigger.takeIf { stop && contracts },
            timeInForce =
                when (o.timeInForce) {
                    TimeInForce.GTC -> "GTC"
                    TimeInForce.IOC -> "IOC"
                    TimeInForce.FOK -> "FOK"
                    TimeInForce.DAY -> error("unreachable")
                },
            orderLinkId = o.clientOrderId,
            reduceOnly = o.reduceOnly && contracts,
            positionIdx = positionIdx,
            orderFilter = "StopOrder".takeIf { stop && !contracts },
            marketUnit = "baseCoin".takeIf { !limited && !contracts },
        )
    }

    private const val RISES = 1
    private const val FALLS = 2
}

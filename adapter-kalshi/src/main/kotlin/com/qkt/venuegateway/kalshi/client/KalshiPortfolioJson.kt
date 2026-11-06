package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.support.JsonReads.instantMs
import com.qkt.venuegateway.support.JsonReads.long
import com.qkt.venuegateway.support.JsonReads.longOrNull
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.needDecimal
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account's JSON, REST answers and socket pushes alike: an order (`GET /portfolio/orders`, a `user_order` push),
 * a fill (`GET /portfolio/fills`, a `fill` push), a settlement, a position and the balance; and a new order's body.
 */
object KalshiPortfolioJson {
    fun order(o: JsonObject): KalshiOrder {
        val created = o.longOrNull("created_ts_ms") ?: o.instantMs("created_time") ?: 0L
        val updated = o.longOrNull("last_updated_ts_ms") ?: o.instantMs("last_update_time") ?: created
        return KalshiOrder(
            orderId = o.need("order_id"),
            clientOrderId = o.text("client_order_id"),
            ticker = o.need("ticker"),
            bookSide = o.need("book_side"),
            status = o.need("status"),
            yesPrice = o.needDecimal("yes_price_dollars"),
            fillCount = o.needDecimal("fill_count_fp"),
            remainingCount = o.needDecimal("remaining_count_fp"),
            initialCount = o.needDecimal("initial_count_fp"),
            fillCost = o.needDecimal("taker_fill_cost_dollars") + o.needDecimal("maker_fill_cost_dollars"),
            createdMs = created,
            updatedMs = updated,
        )
    }

    fun fill(o: JsonObject) =
        KalshiFill(
            fillId = o.text("fill_id") ?: o.need("trade_id"),
            orderId = o.need("order_id"),
            clientOrderId = o.text("client_order_id"),
            ticker = o.text("ticker") ?: o.need("market_ticker"),
            bookSide = o.need("book_side"),
            count = o.needDecimal("count_fp"),
            yesPrice = o.needDecimal("yes_price_dollars"),
            fee = o.decimal("fee_cost") ?: BigDecimal.ZERO,
            timeMs = o.longOrNull("ts_ms") ?: o.instantMs("created_time") ?: (o.long("ts") * 1000),
        )

    fun settlement(o: JsonObject) =
        KalshiSettlement(
            o.need("ticker"),
            o.text("market_result") ?: "",
            o.text("value")?.toInt(),
            o.instantMs("settled_time") ?: error("kalshi settlement of ${o.need("ticker")} without settled_time"),
            o.decimal("fee_cost") ?: BigDecimal.ZERO,
        )

    fun position(o: JsonObject) =
        KalshiPosition(
            o.need("ticker"),
            o.needDecimal("position_fp"),
            o.needDecimal("market_exposure_dollars"),
            o.text("exchange_index")?.toInt(),
        )

    fun balance(o: JsonObject) =
        KalshiBalance(
            o.objects("balance_breakdown").associate { it.need("exchange_index").toInt() to it.needDecimal("balance") },
            o.long("portfolio_value"),
        )

    fun placed(o: JsonObject) =
        KalshiPlaced(o.need("order_id"), o.needDecimal("fill_count"), o.needDecimal("remaining_count"), o.long("ts_ms"))

    /** The body of `POST /portfolio/events/orders`, with the self-trade prevention Kalshi requires. */
    fun body(n: KalshiNewOrder): JsonObject =
        buildJsonObject {
            put("ticker", n.ticker)
            put("side", n.side)
            put("count", n.count.toPlainString())
            put("price", n.price.toPlainString())
            put("time_in_force", n.timeInForce)
            put("self_trade_prevention_type", "taker_at_cross")
            put("client_order_id", n.clientOrderId)
            if (n.reduceOnly) put("reduce_only", true)
        }
}

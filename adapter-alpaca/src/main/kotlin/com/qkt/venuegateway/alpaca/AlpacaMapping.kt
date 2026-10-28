package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.support.JsonReads.instantMs
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.needDecimal
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/** The only place Alpaca's words become adapter-api types. An order, side, type or time in force qkt cannot hold fails naming it. */
object AlpacaMapping {
    private val STATUS =
        mapOf(
            "new" to OrderStatus.WORKING,
            "partially_filled" to OrderStatus.WORKING,
            "done_for_day" to OrderStatus.WORKING,
            "accepted" to OrderStatus.WORKING,
            "pending_new" to OrderStatus.WORKING,
            "accepted_for_bidding" to OrderStatus.WORKING,
            "pending_cancel" to OrderStatus.WORKING,
            "pending_replace" to OrderStatus.WORKING,
            "stopped" to OrderStatus.WORKING,
            "suspended" to OrderStatus.WORKING,
            "calculated" to OrderStatus.WORKING,
            "held" to OrderStatus.WORKING,
            "filled" to OrderStatus.FILLED,
            "canceled" to OrderStatus.CANCELLED,
            "expired" to OrderStatus.CANCELLED,
            "replaced" to OrderStatus.CANCELLED,
            "rejected" to OrderStatus.REJECTED,
        )
    private val TYPES = OrderType.entries.associateBy { it.name.lowercase() }
    private val TIF = TimeInForce.entries.associateBy { it.name.lowercase() }

    /** An order's or an execution's side; a FILL activity names an opening short `sell_short` (measured on paper). */
    fun side(text: String): Side =
        when (text) {
            "buy" -> Side.BUY
            "sell", "sell_short" -> Side.SELL
            else -> error("alpaca side $text")
        }

    /** Alpaca's [order], reported under [label], the id the client placed it with (a replacement carries a suffixed one). */
    fun order(
        order: JsonObject,
        label: String = AlpacaLabels.labelOf(order.need("client_order_id")),
    ): VenueOrder {
        val status = order.need("status")
        val type = order.need("type")
        val tif = order.need("time_in_force")
        return VenueOrder(
            clientOrderId = label,
            venueOrderId = order.need("id"),
            symbol = AlpacaInstruments.code(order.need("symbol")),
            side = side(order.need("side")),
            type = TYPES[type] ?: error("alpaca order type $type has no VGP type"),
            quantity = order.needDecimal("qty"),
            limitPrice = order.decimal("limit_price"),
            stopPrice = order.decimal("stop_price"),
            timeInForce = TIF[tif] ?: error("alpaca time_in_force $tif has no VGP time in force"),
            reduceOnly = order.text("position_intent")?.endsWith("_to_close") == true,
            status = STATUS[status] ?: error("alpaca order status $status is unknown"),
            filledQuantity = order.decimal("filled_qty") ?: BigDecimal.ZERO,
            avgFillPrice = order.decimal("filled_avg_price"),
            rejectReason = if (status == "rejected") "rejected by alpaca" else null,
            createdAtMs = order.instantMs("created_at") ?: error("alpaca order without created_at"),
            updatedAtMs = order.instantMs("updated_at") ?: order.instantMs("created_at")!!,
        )
    }

    /** The execution in trade update [update] (`fill`, `partial_fill`), or null for any other event. */
    fun fill(update: JsonObject): VenueFill? {
        if (update.text("event") !in FILL_EVENTS) return null
        val order = update["order"] as JsonObject
        return VenueFill(
            clientOrderId = AlpacaLabels.labelOf(order.need("client_order_id")),
            venueOrderId = order.need("id"),
            fillId = update.need("execution_id"),
            symbol = AlpacaInstruments.code(order.need("symbol")),
            side = side(order.need("side")),
            quantity = update.needDecimal("qty"),
            price = update.needDecimal("price"),
            timeMs = update.instantMs("timestamp") ?: error("alpaca fill without timestamp"),
        )
    }

    /** The FILL [activity], of the order placed as [label]; its id is `<time>::<execution id>`, the stream's execution id. */
    fun fill(
        activity: JsonObject,
        label: String,
    ): VenueFill =
        VenueFill(
            clientOrderId = label,
            venueOrderId = activity.need("order_id"),
            fillId = activity.need("id").substringAfter("::"),
            symbol = AlpacaInstruments.code(activity.need("symbol")),
            side = side(activity.need("side")),
            quantity = activity.needDecimal("qty"),
            price = activity.needDecimal("price"),
            timeMs = activity.instantMs("transaction_time") ?: error("alpaca fill without transaction_time"),
        )

    /** The account in USD: cash as the balance, margin in use as the initial requirement, the rest of equity free. */
    fun account(account: JsonObject): AccountSnapshot {
        val equity = account.needDecimal("equity")
        val initial = account.decimal("initial_margin") ?: BigDecimal.ZERO
        return AccountSnapshot(
            currency = account.need("currency"),
            balance = account.needDecimal("cash"),
            equity = equity,
            marginUsed = initial,
            marginAvailable = equity - initial,
            initialMargin = initial,
            maintenanceMargin = account.decimal("maintenance_margin"),
        )
    }

    /** One net position, negative when short (Alpaca's `side`; its `qty` is signed the same way). */
    fun position(position: JsonObject): PositionRow {
        val qty = position.needDecimal("qty").abs()
        return PositionRow(
            symbol = AlpacaInstruments.code(position.need("symbol")),
            quantity = if (position.need("side") == "short") qty.negate() else qty,
            avgPrice = position.needDecimal("avg_entry_price"),
        )
    }

    fun quote(message: JsonObject): VenueQuote =
        VenueQuote(
            symbol = AlpacaInstruments.code(message.need("S")),
            bid = message.decimal("bp")?.takeIf { it.signum() > 0 },
            ask = message.decimal("ap")?.takeIf { it.signum() > 0 },
            bidSize = message.decimal("bs"),
            askSize = message.decimal("as"),
            timeMs = message.instantMs("t") ?: error("alpaca quote without t"),
        )

    fun bar(bar: JsonObject): VenueBar =
        VenueBar(
            startMs = Instant.parse(bar.need("t")).toEpochMilli(),
            open = bar.needDecimal("o"),
            high = bar.needDecimal("h"),
            low = bar.needDecimal("l"),
            close = bar.needDecimal("c"),
            volume = bar.needDecimal("v"),
        )

    private val FILL_EVENTS = setOf("fill", "partial_fill")
}

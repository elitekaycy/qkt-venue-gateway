package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.alpaca.client.AlpacaHttpException
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.Retry
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Orders on one Alpaca account, by the client's label. Alpaca has no reduce-only flag, so a reduce-only order is
 * sent for at most the position it reduces (a coin bought pays its fee in the coin, so a whole close of a coin is a
 * little less than was bought) and refused when there is nothing to reduce. A replace is a new Alpaca order whose
 * id carries the label's next generation ([AlpacaLabels]); a lookup follows the chain to its newest order. A label
 * Alpaca already holds (a retried place) answers the order it names instead of a refusal. A cancel and a replace
 * return once Alpaca reads them done, since the host reads the order back by its label straight after. Alpaca's open-order list
 * trails a cancel by a moment (measured on paper: the order read back cancelled while the list still held it), so
 * an order seen final here in the last [settleMs] is left out of [open].
 */
class AlpacaOrderDesk(
    private val trading: AlpacaTrading,
    private val listing: AlpacaListing,
    private val settings: AlpacaSettings,
    private val netPosition: (String) -> BigDecimal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val settleMs: Long = 60_000,
) {
    private val labels = ConcurrentHashMap<String, String>()
    private val final = ConcurrentHashMap<String, Long>()

    /** A share's exits sent as one OCO ([AlpacaExitPairs]); the adapter reads trade updates' labels through it. */
    val pairs = AlpacaExitPairs(trading, clock, sleep)

    fun place(order: NewOrder): VenueOrder {
        val listed = listing.require(order.symbol)
        val quantity = AlpacaOrderRules.quantity(order, listed) { netPosition(order.symbol) }
        val body =
            buildJsonObject {
                put("symbol", listed.symbol)
                put("qty", quantity.toPlainString())
                put("side", order.side.name.lowercase())
                put("type", order.type.name.lowercase())
                put("time_in_force", order.timeInForce.name.lowercase())
                put("client_order_id", order.clientOrderId)
                order.limitPrice?.let { put("limit_price", it.toPlainString()) }
                order.stopPrice?.let { put("stop_price", it.toPlainString()) }
                if (settings.extendedHours &&
                    !listed.crypto &&
                    order.type == OrderType.LIMIT &&
                    order.timeInForce in EXTENDED_TIF
                ) {
                    put("extended_hours", true)
                }
            }
        val placed =
            try {
                trading.submit(body)
            } catch (e: AlpacaHttpException) {
                val retried = AlpacaErrors.code(e) == DUPLICATE && "client_order_id" in e.body
                (if (retried) latest(order.clientOrderId) else null)
                    ?: pairs.afterRefusal(e, order, quantity, listed)
                    ?: throw AlpacaErrors.translate(e)
            } catch (e: java.io.IOException) {
                throw AlpacaErrors.translate(e)
            }
        return AlpacaMapping.order(placed, order.clientOrderId)
    }

    /**
     * Cancels [label]'s order if Alpaca still works it, and waits until Alpaca reads it final: a cancel is answered
     * before it is done (`pending_cancel`), and the host reads the order back at once. An unknown label is no error.
     */
    fun cancel(label: String) {
        val order = AlpacaErrors.venue { latest(label) } ?: return
        if (AlpacaMapping.order(order, label).status != OrderStatus.WORKING) return
        AlpacaErrors.venue { trading.cancel(order.need("id")) }
        Retry.until(SETTLE_POLLS, SETTLE_POLL_MS, sleep) {
            AlpacaErrors
                .venue {
                    latest(
                        label,
                    )
                }?.takeIf { AlpacaMapping.order(it, label).status != OrderStatus.WORKING }
        }
        final[label] = clock()
    }

    /**
     * Replaces [label]'s order (false when Alpaca has no such order) and waits until a lookup by the label reaches the
     * replacement, which the host reads back at once: until Alpaca marks the original `replaced`, it is still the
     * newest order of the chain.
     */
    fun modify(
        label: String,
        change: OrderChange,
    ): Boolean {
        val order = AlpacaErrors.venue { latest(label) } ?: return false
        val body =
            buildJsonObject {
                change.quantity?.let { put("qty", it.toPlainString()) }
                change.limitPrice?.let { put("limit_price", it.toPlainString()) }
                change.stopPrice?.let { put("stop_price", it.toPlainString()) }
                // An OCO's stop leg carries Alpaca's own id until its first replacement names it.
                val current = order.need("client_order_id").takeIf { AlpacaLabels.labelOf(it) == label } ?: label
                put("client_order_id", AlpacaLabels.next(current))
            }
        val replacement = AlpacaErrors.venue { trading.replace(order.need("id"), body) }.need("id")
        Retry.until(SETTLE_POLLS, SETTLE_POLL_MS, sleep) {
            AlpacaErrors.venue { latest(label) }?.takeIf {
                it.need("id") ==
                    replacement
            }
        }
        return true
    }

    fun byLabel(label: String): VenueOrder? =
        AlpacaErrors.venue { latest(label) }?.let { AlpacaMapping.order(it, label) }

    fun open(): List<VenueOrder> {
        val now = clock()
        final.entries.removeIf { now - it.value > settleMs }
        return AlpacaErrors
            .venue { trading.openOrders() }
            .flatMap { order ->
                listOf(AlpacaMapping.order(order)) +
                    order.objects("legs").map { AlpacaMapping.order(it, pairs.label(order, it)) }
            }.filter { !final.containsKey(it.clientOrderId) }
    }

    /** Executions from [fromMs] to [toMs], each under the label of the order it filled. */
    fun fills(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFill> =
        AlpacaErrors.venue { trading.fills(fromMs, toMs) }.map { activity ->
            val orderId = activity.need("order_id")
            val label =
                labels.getOrPut(orderId) {
                    AlpacaErrors.venue { trading.order(orderId) }?.let(pairs::label) ?: orderId
                }
            AlpacaMapping.fill(activity, label)
        }

    /** The newest order of [label]'s replace chain, or null when Alpaca never saw the label. */
    private fun latest(label: String): JsonObject? {
        var order = trading.orderByClientId(label) ?: return pairs.leg(label)
        if (order.text("status") == "canceled" && order.text("order_class") == null && order.text("type") == "limit") {
            order = pairs.successor(label, order) ?: order
        }
        while (order.text("status") == "replaced") order = order.text("replaced_by")?.let(trading::order) ?: break
        return order
    }

    private companion object {
        const val DUPLICATE = "40010001"

        /** The times in force Alpaca takes `extended_hours` on (a limit only; measured: DAY and GTC accepted). */
        private val EXTENDED_TIF = setOf(TimeInForce.DAY, TimeInForce.GTC)
        const val SETTLE_POLLS = 20
        const val SETTLE_POLL_MS = 250L
    }
}

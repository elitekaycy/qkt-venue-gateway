package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.alpaca.client.AlpacaHttpException
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.support.JsonReads.instantMs
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.Retry
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * A share's two exits held as one Alpaca OCO. Alpaca holds a position's shares for the first resting sell and refuses
 * a second one (403 `40310000`, measured on paper), so a bracket's stop sent after its target is refused unless both
 * travel as one `oco` order. When that refusal comes, the target it collides with (the newest working plain limit of
 * the same side and quantity, placed in the last [windowMs]) is cancelled and re-sent with the stop as an OCO: the
 * target keeps its label ([AlpacaLabels.paired]), the stop is answered as the OCO's stop leg under its own label. The
 * target's cancellation is never reported, since the OCO carries it on. Should Alpaca refuse the OCO, the target is
 * placed again as it was and the stop's refusal stands. Alpaca has no OCO for a coin.
 */
class AlpacaExitPairs(
    private val trading: AlpacaTrading,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val windowMs: Long = 60_000,
) {
    private val log = LoggerFactory.getLogger(AlpacaExitPairs::class.java)
    private val stops = ConcurrentHashMap<String, String>()
    private val swapped = ConcurrentHashMap.newKeySet<String>()

    /**
     * The stop leg answering share stop [stop] for [quantity], which Alpaca refused with [failure], re-sent with its
     * target; null when the refusal was not for shares a resting sell holds, or there is no target to pair.
     */
    fun afterRefusal(
        failure: AlpacaHttpException,
        stop: NewOrder,
        quantity: BigDecimal,
        listed: AlpacaListed,
    ): JsonObject? {
        // Alpaca's 40310000 also refuses buying power; a refusal for held shares names them.
        val held = failure.status == FORBIDDEN && AlpacaErrors.code(failure) == HELD && HELD_FIELD in failure.body
        if (listed.crypto || !held || (stop.type != OrderType.STOP && stop.type != OrderType.STOP_LIMIT)) return null
        val target = target(stop, quantity, listed.symbol) ?: return null
        val id = target.need("id")
        val clientOrderId = AlpacaLabels.paired(target.need("client_order_id"), stop.clientOrderId)
        if (clientOrderId.length > AlpacaLabels.MAX_LENGTH) return null
        swapped += id
        AlpacaErrors.venue { trading.cancel(id) }
        val ended = Retry.until(POLLS, POLL_MS, sleep) { AlpacaErrors.venue { trading.order(id) }?.takeIf(::ended) }
        if (ended?.text("status") != "canceled") return null.also { swapped -= id }
        val oco =
            try {
                trading.submit(oco(target, stop, quantity, clientOrderId))
            } catch (e: AlpacaHttpException) {
                log.warn("alpaca refused the oco of {} and {}: {}", target.need("id"), stop.clientOrderId, e.body)
                AlpacaErrors.venue { trading.submit(again(target)) }
                return null
            }
        return oco.objects("legs").first().also { stops[it.need("id")] = stop.clientOrderId }
    }

    /** Whether trade update [event] of [order] is the cancellation of a target re-sent as an OCO, never reported. */
    fun hides(
        event: String?,
        order: JsonObject,
    ): Boolean = event == "canceled" && swapped.remove(order.need("id"))

    /** The label [order] is reported under: an OCO's stop leg under the stop's label, read from the OCO. */
    fun label(order: JsonObject): String {
        if (order.text("group_role") != STOP_LEG) return AlpacaLabels.labelOf(order.need("client_order_id"))
        return stops.getOrPut(order.need("id")) {
            AlpacaErrors
                .venue { trading.orders("all", symbol = order.need("symbol")) }
                .firstOrNull { it.text("group_id") == order.text("group_id") && it.text("group_role") != STOP_LEG }
                ?.let { AlpacaLabels.stopOf(it.need("client_order_id")) }
                ?: AlpacaLabels.labelOf(order.need("client_order_id"))
        }
    }

    /** The label [leg] of OCO [parent] is reported under. */
    fun label(
        parent: JsonObject,
        leg: JsonObject,
    ): String =
        AlpacaLabels.stopOf(parent.need("client_order_id"))?.also { stops[leg.need("id")] = it }
            ?: AlpacaLabels.labelOf(leg.need("client_order_id"))

    /** The stop leg now carrying [label], when [label] was paired with a target; null otherwise. */
    fun leg(label: String): JsonObject? =
        trading
            .orders("all")
            .firstOrNull { AlpacaLabels.stopOf(it.need("client_order_id")) == label }
            ?.objects("legs")
            ?.firstOrNull()

    /** The order that carries on [cancelled] (placed as [label]) when it was a target re-sent as an OCO. */
    fun successor(
        label: String,
        cancelled: JsonObject,
    ): JsonObject? =
        trading.orders("all", symbol = cancelled.need("symbol")).firstOrNull {
            val id = it.need("client_order_id")
            id != cancelled.need("client_order_id") && AlpacaLabels.labelOf(id) == label
        }

    private fun target(
        stop: NewOrder,
        quantity: BigDecimal,
        symbol: String,
    ): JsonObject? =
        AlpacaErrors.venue { trading.orders("open", symbol = symbol) }.firstOrNull {
            it.text("order_class") == null &&
                it.text("type") == "limit" &&
                it.text("side") == stop.side.name.lowercase() &&
                it.decimal("qty")?.compareTo(quantity) == 0 &&
                AlpacaLabels.stopOf(it.need("client_order_id")) == null &&
                clock() - (it.instantMs("created_at") ?: 0L) <= windowMs
        }

    private fun oco(
        target: JsonObject,
        stop: NewOrder,
        quantity: BigDecimal,
        clientOrderId: String,
    ) = buildJsonObject {
        put("symbol", target.need("symbol"))
        put("qty", quantity.toPlainString())
        put("side", target.need("side"))
        put("type", "limit")
        put("time_in_force", stop.timeInForce.name.lowercase())
        put("order_class", "oco")
        put("client_order_id", clientOrderId)
        putJsonObject("take_profit") { put("limit_price", target.need("limit_price")) }
        putJsonObject("stop_loss") {
            put("stop_price", requireNotNull(stop.stopPrice) { "a stop without stop_price" }.toPlainString())
            if (stop.type == OrderType.STOP_LIMIT) stop.limitPrice?.let { put("limit_price", it.toPlainString()) }
        }
    }

    private fun again(target: JsonObject) =
        buildJsonObject {
            listOf("symbol", "qty", "side", "type", "time_in_force", "limit_price").forEach { put(it, target.need(it)) }
            put("client_order_id", AlpacaLabels.next(target.need("client_order_id")))
        }

    private fun ended(order: JsonObject) = order.text("status") in ENDED

    private companion object {
        const val STOP_LEG = "stop_loss"
        const val HELD = "40310000"
        const val HELD_FIELD = "held_for_orders"
        const val FORBIDDEN = 403
        const val POLLS = 20
        const val POLL_MS = 250L
        val ENDED = setOf("canceled", "filled", "expired", "rejected")
    }
}

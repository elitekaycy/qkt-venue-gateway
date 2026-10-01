package com.qkt.venued.host.wire

import com.qkt.venued.adapter.Cost
import com.qkt.venued.adapter.NewOrder
import com.qkt.venued.adapter.OrderType
import com.qkt.venued.adapter.VenueFill
import com.qkt.venued.adapter.VenueOrder
import com.qkt.venued.adapter.VenueSettlement
import com.qkt.vgp.WireCost
import com.qkt.vgp.WireFill
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireSettlement
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal

/** A request the client got wrong; served as `400 invalid_request`. */
class InvalidRequestException(
    message: String,
) : RuntimeException(message)

/**
 * The one translation between the wire (decimal strings, lowercase enums) and the adapters' neutral
 * types. Decimals are written with [BigDecimal.toPlainString] and read exactly; an unknown enum or a
 * malformed decimal is refused, never guessed.
 */
object WireMapping {
    /** [body] as an order to place; throws [InvalidRequestException] naming the bad field. */
    fun newOrder(body: WireSubmit): NewOrder {
        val type = enumOf<OrderType>("type", body.type)
        val order =
            NewOrder(
                clientOrderId =
                    body.clientOrderId.also {
                        field(
                            "client_order_id",
                            it.isNotBlank() && it.length <= MAX_ID,
                        )
                    },
                symbol = body.symbol.also { field("symbol", it.isNotBlank()) },
                side = enumOf("side", body.side),
                type = type,
                quantity = decimal("quantity", body.quantity).also { field("quantity", it.signum() > 0) },
                limitPrice = body.limitPrice?.let { decimal("limit_price", it) },
                stopPrice = body.stopPrice?.let { decimal("stop_price", it) },
                timeInForce = enumOf("time_in_force", body.timeInForce),
                reduceOnly = body.reduceOnly,
            )
        val needsLimit = type == OrderType.LIMIT || type == OrderType.STOP_LIMIT
        val needsStop = type == OrderType.STOP || type == OrderType.STOP_LIMIT
        field("limit_price", (order.limitPrice != null) == needsLimit)
        field("stop_price", (order.stopPrice != null) == needsStop)
        return order
    }

    fun order(o: VenueOrder): WireOrder =
        WireOrder(
            clientOrderId = o.clientOrderId,
            venueOrderId = o.venueOrderId,
            symbol = o.symbol,
            side = o.side.wire(),
            type = o.type.wire(),
            quantity = o.quantity.toPlainString(),
            limitPrice = o.limitPrice?.toPlainString(),
            stopPrice = o.stopPrice?.toPlainString(),
            timeInForce = o.timeInForce.wire(),
            reduceOnly = o.reduceOnly,
            status = o.status.wire(),
            filledQuantity = o.filledQuantity.toPlainString(),
            avgFillPrice = o.avgFillPrice?.toPlainString(),
            rejectReason = o.rejectReason,
            createdAt = o.createdAtMs,
            updatedAt = o.updatedAtMs,
        )

    fun fill(f: VenueFill): WireFill =
        WireFill(
            f.clientOrderId,
            f.venueOrderId,
            f.fillId,
            f.symbol,
            f.side.wire(),
            f.quantity.toPlainString(),
            f.price.toPlainString(),
            f.timeMs,
            f.costs.map(::cost),
        )

    fun settlement(s: VenueSettlement): WireSettlement =
        WireSettlement(s.symbol, s.price.toPlainString(), s.timeMs, s.costs.map(::cost))

    /** The order [body] became when it never reached the venue or the venue refused it, for [reason] at [nowMs]. */
    fun rejected(
        body: WireSubmit,
        reason: String,
        nowMs: Long,
    ): WireOrder =
        WireOrder(
            clientOrderId = body.clientOrderId,
            symbol = body.symbol,
            side = body.side,
            type = body.type,
            quantity = body.quantity,
            limitPrice = body.limitPrice,
            stopPrice = body.stopPrice,
            timeInForce = body.timeInForce,
            reduceOnly = body.reduceOnly,
            status = "rejected",
            rejectReason = reason,
            createdAt = nowMs,
            updatedAt = nowMs,
        )

    private fun cost(c: Cost) = WireCost(c.kind.wire(), c.amount.toPlainString(), c.currency)

    /** An enum's wire name: lowercase. */
    fun Enum<*>.wire(): String = name.lowercase()

    private inline fun <reified E : Enum<E>> enumOf(
        field: String,
        value: String,
    ): E =
        enumValues<E>().firstOrNull { it.name.lowercase() == value }
            ?: throw InvalidRequestException(
                "$field '$value' is not one of ${enumValues<E>().joinToString { it.name.lowercase() }}",
            )

    private fun decimal(
        field: String,
        value: String,
    ): BigDecimal = value.toBigDecimalOrNull() ?: throw InvalidRequestException("$field '$value' is not a decimal")

    private fun field(
        name: String,
        valid: Boolean,
    ) {
        if (!valid) throw InvalidRequestException("$name is missing or invalid")
    }

    /** VGP v1's longest client order id. */
    const val MAX_ID = 64
}

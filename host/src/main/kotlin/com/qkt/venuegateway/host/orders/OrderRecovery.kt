package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.host.wire.WireMapping
import com.qkt.vgp.WireSubmit
import java.math.MathContext

/** What became of a sent order: the [order] as the venue reports it or as rebuilt, and the [fills] found for it. */
data class RecoveredOrder(
    val order: VenueOrder,
    val fills: List<VenueFill>,
)

/**
 * Finds what became of an order the client sent whose answer never reached the journal. A venue keeps
 * working orders but may forget closed ones while keeping their trades (Deribit answers by label for
 * under an hour; measured on testnet 2026-10-01), so the order is looked up by label first and, failing
 * that, rebuilt from the sent body and its fills of the last [lookbackMs]: filled when they cover its
 * quantity, else ended (cancelled) with what filled, at their quantity-weighted price. Only when the
 * venue holds neither is the order unknown to it, so it is never written off, or sent again, while a
 * trace of it exists.
 */
class OrderRecovery(
    private val venue: VenueAdapter,
    private val clock: () -> Long,
    private val lookbackMs: Long = SEVEN_DAYS_MS,
) {
    /** The order [body] became, or null when the venue holds no trace of it. */
    fun find(body: WireSubmit): RecoveredOrder? {
        venue.orderByLabel(body.clientOrderId)?.let { return RecoveredOrder(it, emptyList()) }
        val now = clock()
        val fills =
            venue
                .fills(
                    now - lookbackMs,
                    now,
                ).filter { it.clientOrderId == body.clientOrderId }
                .sortedBy { it.timeMs }
        if (fills.isEmpty()) return null
        val sent = WireMapping.newOrder(body)
        val filled = fills.sumOf { it.quantity }
        val average = fills.sumOf { it.quantity * it.price }.divide(filled, MathContext.DECIMAL64).stripTrailingZeros()
        val order =
            VenueOrder(
                clientOrderId = sent.clientOrderId,
                venueOrderId = fills.first().venueOrderId,
                symbol = sent.symbol,
                side = sent.side,
                type = sent.type,
                quantity = sent.quantity,
                limitPrice = sent.limitPrice,
                stopPrice = sent.stopPrice,
                timeInForce = sent.timeInForce,
                reduceOnly = sent.reduceOnly,
                status = if (filled >= sent.quantity) OrderStatus.FILLED else OrderStatus.CANCELLED,
                filledQuantity = filled,
                avgFillPrice = average,
                rejectReason = null,
                createdAtMs = fills.first().timeMs,
                updatedAtMs = fills.last().timeMs,
            )
        return RecoveredOrder(order, fills)
    }

    private companion object {
        const val SEVEN_DAYS_MS = 7 * 24 * 3_600_000L
    }
}

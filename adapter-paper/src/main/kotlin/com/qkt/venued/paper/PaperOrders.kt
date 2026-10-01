package com.qkt.venued.paper

import com.qkt.venued.adapter.Cost
import com.qkt.venued.adapter.CostKind
import com.qkt.venued.adapter.NewOrder
import com.qkt.venued.adapter.OrderStatus
import com.qkt.venued.adapter.VenueFill
import com.qkt.venued.adapter.VenueOrder
import com.qkt.venued.adapter.VenueSettlement
import java.math.BigDecimal

/**
 * The paper venue's records: every order by its label, every fill and settlement, and the stop orders
 * already triggered; and the two ways an order leaves the book, filled (booked on the [ledger], less
 * [feeRate] × notional in [currency]) or ended. Not thread-safe: the adapter calls it under one lock.
 */
class PaperOrders(
    private val ledger: PaperLedger,
    private val currency: String,
    private val feeRate: BigDecimal,
    private val contractSizeOf: (String) -> BigDecimal,
) {
    val orders = LinkedHashMap<String, VenueOrder>()
    val fills = ArrayList<VenueFill>()
    val settlements = ArrayList<VenueSettlement>()
    val triggered = HashSet<String>()
    var fillCount = 0L

    internal fun fill(
        order: VenueOrder,
        price: BigDecimal,
        nowMs: Long,
    ): PaperChange {
        val fee = feeRate.multiply(price).multiply(order.quantity).multiply(contractSizeOf(order.symbol))
        ledger.apply(order.symbol, order.side, order.quantity, price)
        ledger.balance = ledger.balance.subtract(fee)
        val costs = if (fee.signum() == 0) emptyList() else listOf(Cost(CostKind.COMMISSION, fee, currency))
        val fill =
            VenueFill(
                order.clientOrderId,
                order.venueOrderId,
                "paper-${++fillCount}",
                order.symbol,
                order.side,
                order.quantity,
                price,
                nowMs,
                costs,
            )
        fills += fill
        val filled =
            order.copy(
                status = OrderStatus.FILLED,
                filledQuantity = order.quantity,
                avgFillPrice = price,
                updatedAtMs = nowMs,
            )
        orders[order.clientOrderId] = filled
        triggered -= order.clientOrderId
        return PaperChange(listOf(filled), listOf(fill))
    }

    internal fun end(
        order: VenueOrder,
        status: OrderStatus,
        nowMs: Long,
        reason: String?,
    ): PaperChange {
        val ended = order.copy(status = status, rejectReason = reason, updatedAtMs = nowMs)
        orders[order.clientOrderId] = ended
        triggered -= order.clientOrderId
        return PaperChange(listOf(ended))
    }

    internal fun newOrder(
        o: NewOrder,
        nowMs: Long,
    ) = VenueOrder(
        o.clientOrderId,
        "paper-o-${orders.size + 1}",
        o.symbol,
        o.side,
        o.type,
        o.quantity,
        o.limitPrice,
        o.stopPrice,
        o.timeInForce,
        o.reduceOnly,
        OrderStatus.WORKING,
        BigDecimal.ZERO,
        null,
        null,
        nowMs,
        nowMs,
    )
}

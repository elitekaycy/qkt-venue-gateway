package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueOrder
import org.assertj.core.api.Assertions.assertThat

/**
 * The wire rule that a market order never rests: one larger than the book fills what the venue offers at
 * that moment and ends (`cancelled` with what filled, or `filled`); no remainder is left working.
 */
internal object RemainderChecks {
    /**
     * Places the market [order] (larger than the book), checks it ends within [pushTimeoutMs] with no part
     * left working and the position moved by exactly what filled, then closes what filled with reduce-only
     * market orders labelled by [label], which on a thin book may each fill only part.
     */
    fun overrun(
        adapter: VenueAdapter,
        orders: KitOrders,
        order: NewOrder,
        label: () -> String,
        pushTimeoutMs: Long,
    ) {
        val before = ContractChecks.net(adapter.positions(), order.symbol)
        val id = order.clientOrderId
        orders.place(adapter, order)
        val ended = ended(adapter, id, pushTimeoutMs)

        assertThat(ended.status).describedAs("status of $id").isIn(OrderStatus.FILLED, OrderStatus.CANCELLED)
        assertThat(ended.filledQuantity).describedAs("filled quantity of $id").isLessThanOrEqualTo(order.quantity)
        assertThat(adapter.openOrders().map { it.clientOrderId }).describedAs("open orders").doesNotContain(id)
        assertThat(ContractChecks.net(adapter.positions(), order.symbol))
            .describedAs("position after $id")
            .isEqualByComparingTo(before + ContractChecks.signed(order.side, ended.filledQuantity))

        val back = if (order.side == Side.BUY) Side.SELL else Side.BUY
        repeat(CLOSE_ATTEMPTS) {
            val open = (ContractChecks.net(adapter.positions(), order.symbol) - before).abs()
            if (open.signum() == 0) return
            val close = order.copy(clientOrderId = label(), side = back, quantity = open, reduceOnly = true)
            orders.place(adapter, close)
            ended(adapter, close.clientOrderId, pushTimeoutMs)
            Thread.sleep(CLOSE_PAUSE_MS)
        }
        assertThat(
            ContractChecks.net(adapter.positions(), order.symbol),
        ).describedAs("closed").isEqualByComparingTo(before)
    }

    /** The order [id] once the venue reports it ended, failing if any part of it is still working after [timeoutMs]. */
    private fun ended(
        adapter: VenueAdapter,
        id: String,
        timeoutMs: Long,
    ): VenueOrder {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val order = adapter.orderByLabel(id)
            if (order != null && order.status != OrderStatus.WORKING) return order
            check(System.currentTimeMillis() < deadline) { "market order $id still has a part working: $order" }
            Thread.sleep(POLL_MS)
        }
    }

    private const val CLOSE_ATTEMPTS = 10
    private const val CLOSE_PAUSE_MS = 2_000L
    private const val POLL_MS = 250L
}

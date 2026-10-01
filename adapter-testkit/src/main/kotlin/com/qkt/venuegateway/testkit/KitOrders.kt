package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueOrder
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat

/**
 * The orders one contract run places: labels unique to the run (a venue remembers labels, so a rerun
 * never collides with an earlier one), and a record of every order so the run leaves nothing working.
 */
internal class KitOrders(
    private val pushTimeoutMs: Long,
) {
    private val run = System.currentTimeMillis().toString(36)
    private var sequence = 0
    private val placed = mutableListOf<String>()

    /** A label no earlier run used. */
    fun label(): String = "kit-$run-${++sequence}"

    /** Places [order] on [adapter], remembering it for [cancelWorking]. */
    fun place(
        adapter: VenueAdapter,
        order: NewOrder,
    ): VenueOrder {
        placed += order.clientOrderId
        return adapter.place(order)
    }

    /** Places [order] and waits until its pushed fills add up to it and the venue reports it filled. */
    fun fill(
        adapter: VenueAdapter,
        listener: RecordingListener,
        order: NewOrder,
    ) {
        place(adapter, order)
        val id = order.clientOrderId
        listener.await("fills of $id adding up to ${order.quantity}", pushTimeoutMs) {
            listener.fills
                .filter { it.clientOrderId == id }
                .fold(BigDecimal.ZERO) { sum, fill -> sum + fill.quantity }
                .compareTo(order.quantity) == 0
        }
        val filled = adapter.orderByLabel(id)
        assertThat(filled?.status).describedAs("status of $id").isEqualTo(OrderStatus.FILLED)
        assertThat(filled?.filledQuantity).describedAs("filled quantity of $id").isEqualByComparingTo(order.quantity)
    }

    /** Cancels every order this run placed that [adapter] still reports working. */
    fun cancelWorking(adapter: VenueAdapter) {
        placed.filter { adapter.orderByLabel(it)?.status == OrderStatus.WORKING }.forEach { adapter.cancel(it) }
    }
}

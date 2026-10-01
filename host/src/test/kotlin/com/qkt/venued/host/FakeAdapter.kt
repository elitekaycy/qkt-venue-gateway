package com.qkt.venued.host

import com.qkt.venued.adapter.AccountSnapshot
import com.qkt.venued.adapter.Accounting
import com.qkt.venued.adapter.AdapterListener
import com.qkt.venued.adapter.Instrument
import com.qkt.venued.adapter.InstrumentKind
import com.qkt.venued.adapter.NewOrder
import com.qkt.venued.adapter.OrderChange
import com.qkt.venued.adapter.OrderStatus
import com.qkt.venued.adapter.PositionRow
import com.qkt.venued.adapter.Positions
import com.qkt.venued.adapter.TradeMode
import com.qkt.venued.adapter.VenueAdapter
import com.qkt.venued.adapter.VenueBar
import com.qkt.venued.adapter.VenueFill
import com.qkt.venued.adapter.VenueIdentity
import com.qkt.venued.adapter.VenueOrder
import com.qkt.venued.adapter.VenueRefusedException
import com.qkt.venued.adapter.VenueSettlement
import com.qkt.venued.adapter.VenueUnavailableException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** An in-memory venue for host tests: places orders as working, and can refuse, be unreachable or lose an answer. */
internal class FakeAdapter : VenueAdapter {
    override val id = "fake"
    override val version = "1"
    val orders = ConcurrentHashMap<String, VenueOrder>()
    val placed = CopyOnWriteArrayList<String>()
    val net = ConcurrentHashMap<String, BigDecimal>()
    val fills = CopyOnWriteArrayList<VenueFill>()
    val settlements = CopyOnWriteArrayList<VenueSettlement>()
    var listener: AdapterListener? = null

    @Volatile var refuseNext: String? = null

    @Volatile var unreachable = false

    /** The next place reaches the venue, but its answer is lost on the way back. */
    @Volatile var loseNextAnswer = false

    override fun connect(listener: AdapterListener) {
        this.listener = listener
    }

    override fun identity() = VenueIdentity("7", TradeMode.DEMO, "USDC")

    override fun instruments() =
        listOf(
            Instrument(
                "BTC_USDC-PERPETUAL",
                InstrumentKind.PERPETUAL,
                "USDC",
                BigDecimal.ONE,
                BigDecimal("0.5"),
                BigDecimal("0.001"),
                BigDecimal("0.001"),
            ),
        )

    override fun account() =
        AccountSnapshot("USDC", BigDecimal("10000"), BigDecimal("10000"), BigDecimal.ZERO, BigDecimal("10000"))

    override fun positions() =
        Positions(Accounting.NETTING, net.map { (s, q) -> PositionRow(s, q, BigDecimal("84000")) })

    override fun openOrders() = orders.values.filter { it.status == OrderStatus.WORKING }

    override fun place(order: NewOrder): VenueOrder {
        if (unreachable) throw VenueUnavailableException("venue down")
        refuseNext?.let {
            refuseNext = null
            throw VenueRefusedException(it)
        }
        val placedOrder =
            VenueOrder(
                order.clientOrderId,
                "v-${placed.size + 1}",
                order.symbol,
                order.side,
                order.type,
                order.quantity,
                order.limitPrice,
                order.stopPrice,
                order.timeInForce,
                order.reduceOnly,
                OrderStatus.WORKING,
                BigDecimal.ZERO,
                null,
                null,
                1,
                1,
            )
        orders[order.clientOrderId] = placedOrder
        placed += order.clientOrderId
        if (loseNextAnswer) {
            loseNextAnswer = false
            throw VenueUnavailableException("answer lost")
        }
        return placedOrder
    }

    override fun cancel(clientOrderId: String): VenueOrder? =
        orders.computeIfPresent(clientOrderId) { _, o -> o.copy(status = OrderStatus.CANCELLED, updatedAtMs = 2) }

    override fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): VenueOrder? = orders[clientOrderId]

    override fun orderByLabel(clientOrderId: String): VenueOrder? = orders[clientOrderId]

    override fun fills(
        fromMs: Long,
        toMs: Long,
    ) = fills.filter { it.timeMs in fromMs..toMs }

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ) = settlements.filter { it.timeMs in fromMs..toMs }

    override fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = emptyList<VenueBar>()

    override fun subscribeQuotes(
        codes: Set<String>,
        roots: Set<String>,
    ) {}

    override fun close() {}
}

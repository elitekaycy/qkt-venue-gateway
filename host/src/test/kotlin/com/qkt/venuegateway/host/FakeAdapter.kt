package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.adapter.VenueUnavailableException
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
            Instrument(
                "BTC_USDC-9OCT26-82000-P",
                InstrumentKind.OPTION,
                "USDC",
                BigDecimal.ONE,
                BigDecimal("5"),
                BigDecimal("0.01"),
                BigDecimal("0.01"),
                expiryMs = 1_791_532_800_000L,
                strike = BigDecimal("82000"),
                right = "put",
                underlying = "BTC_USDC",
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

    val bars = CopyOnWriteArrayList<VenueBar>()
    val barCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
    val subscriptions = CopyOnWriteArrayList<Pair<Set<String>, Set<String>>>()

    override fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = bars.toList().also { barCalls += fromMs to toMs }

    override fun subscribeQuotes(
        codes: Set<String>,
        roots: Set<String>,
    ) {
        subscriptions += codes to roots
    }

    override fun close() {}
}

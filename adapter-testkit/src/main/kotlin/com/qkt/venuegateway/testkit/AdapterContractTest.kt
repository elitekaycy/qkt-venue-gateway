package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueRefusedException
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The behaviour the gateway host relies on from every adapter. An adapter's own test extends this and
 * supplies the adapter and three orders only its venue can name; passing it means the host and qkt
 * treat the adapter exactly as they treat every other. It runs against a venue's test environment:
 * it places real orders, cancels what it leaves working, and flattens what it fills.
 *
 * ```
 * class MyVenueContractTest : AdapterContractTest() {
 *     override fun newAdapter(stateDir: Path) = MyVenueAdapter(...)
 *     override fun restingOrder(clientOrderId: String) = NewOrder(clientOrderId, "BTC-PERP", Side.BUY, ...)
 *     ...
 * }
 * ```
 */
abstract class AdapterContractTest {
    /** A connected-ready adapter keeping its private state in [stateDir]; a second call on it is a restart. */
    protected abstract fun newAdapter(stateDir: Path): VenueAdapter

    /** An order the venue accepts and leaves working: a limit far from the market. */
    protected abstract fun restingOrder(clientOrderId: String): NewOrder

    /** An order the venue fills at once, at its smallest size. */
    protected abstract fun fillingOrder(clientOrderId: String): NewOrder

    /** An order that undoes [fillingOrder]: the other side, the same size, reduce-only. */
    protected abstract fun closingOrder(clientOrderId: String): NewOrder

    /** An order the venue itself refuses, such as a size off its volume step. */
    protected abstract fun refusedOrder(clientOrderId: String): NewOrder

    /** A listed code with recent trading, for bars, quotes and open interest. */
    protected abstract val activeCode: String

    /** A listed perpetual, whose funding rates are checked when the adapter declares funding rates. */
    protected open val perpetualCode: String? = null

    /** A listed option the venue quotes now, whose quotes are checked when the adapter declares option marks. */
    protected open val optionCode: String? = null

    /** The bar length checked; the venue must serve it. */
    protected open val barWindowMs: Long = 60_000L

    /** How long a push may take to arrive. */
    protected open val pushTimeoutMs: Long = 30_000L

    private val adapters = mutableListOf<VenueAdapter>()
    private val orders by lazy { KitOrders(pushTimeoutMs) }

    private fun label() = orders.label()

    private fun connect(stateDir: Path): Pair<VenueAdapter, RecordingListener> {
        val listener = RecordingListener()
        val adapter = newAdapter(stateDir).also { it.connect(listener) }
        adapters += adapter
        listener.await("the venue link to come up", pushTimeoutMs) { listener.connections.contains(true) }
        return adapter to listener
    }

    private fun VenueAdapter.placeTracked(order: NewOrder) = orders.place(this, order)

    @AfterEach
    fun leaveNothingWorking() {
        adapters.lastOrNull()?.let(orders::cancelWorking)
        adapters.forEach { it.close() }
    }

    @Test
    fun `the adapter names its account and lists well-formed instruments, including every code the kit trades`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = connect(dir)
        val identity = adapter.identity()
        val instruments = adapter.instruments()

        assertThat(identity.login).isNotBlank
        assertThat(identity.currency).isNotBlank
        ContractChecks.listing(instruments)
        val codes = listOf(restingOrder("x"), fillingOrder("x")).map { it.symbol } + activeCode
        assertThat(instruments.map { it.code }).containsAll(codes)
    }

    @Test
    fun `a resting order is pushed, found by its label, listed open and cancelled, and unknown labels are null`(
        @TempDir dir: Path,
    ) {
        val (adapter, listener) = connect(dir)
        val id = label()

        val order = adapter.placeTracked(restingOrder(id))

        assertThat(order.clientOrderId).isEqualTo(id)
        assertThat(order.status).isEqualTo(OrderStatus.WORKING)
        assertThat(order.filledQuantity).isZero
        listener.await("the order push of $id", pushTimeoutMs) { listener.orders.any { it.clientOrderId == id } }
        assertThat(adapter.orderByLabel(id)?.status).isEqualTo(OrderStatus.WORKING)
        assertThat(adapter.openOrders().map { it.clientOrderId }).contains(id)

        assertThat(adapter.cancel(id)?.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(adapter.orderByLabel(id)?.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(adapter.openOrders().map { it.clientOrderId }).doesNotContain(id)
        assertThat(adapter.cancel(label())).isNull()
        assertThat(adapter.orderByLabel(label())).isNull()
    }

    @Test
    fun `a filled order pushes unique fills adding up to it, found again by time, and moves the position by it`(
        @TempDir dir: Path,
    ) {
        val (adapter, listener) = connect(dir)
        val opening = fillingOrder(label())
        val before = ContractChecks.net(adapter.positions(), opening.symbol)
        val fromMs = System.currentTimeMillis() - CLOCK_SKEW_MS

        orders.fill(adapter, listener, opening)
        val fills = listener.fills.filter { it.clientOrderId == opening.clientOrderId }
        assertThat(fills.map { it.fillId }).doesNotHaveDuplicates()
        fills.forEach {
            assertThat(it.symbol).isEqualTo(opening.symbol)
            assertThat(it.side).isEqualTo(opening.side)
            assertThat(it.price).isPositive
        }
        val found = adapter.fills(fromMs, System.currentTimeMillis() + CLOCK_SKEW_MS)
        assertThat(found.filter { it.clientOrderId == opening.clientOrderId }.map { it.fillId })
            .containsExactlyInAnyOrderElementsOf(fills.map { it.fillId })
        assertThat(ContractChecks.net(adapter.positions(), opening.symbol))
            .isEqualByComparingTo(before + ContractChecks.signed(opening.side, opening.quantity))

        orders.fill(adapter, listener, closingOrder(label()))
        assertThat(ContractChecks.net(adapter.positions(), opening.symbol)).isEqualByComparingTo(before)
    }

    @Test
    fun `an order the venue refuses throws its typed refusal and leaves nothing working`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = connect(dir)
        val order = refusedOrder(label())

        assertThatThrownBy { adapter.placeTracked(order) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(adapter.openOrders().map { it.clientOrderId }).doesNotContain(order.clientOrderId)
    }

    @Test
    fun `bars, quotes and option marks, when declared, are well formed and quote the bid at or below the ask`(
        @TempDir dir: Path,
    ) {
        val (adapter, listener) = connect(dir)
        CapabilityChecks.barsAndQuotes(adapter, listener, activeCode, barWindowMs, pushTimeoutMs)
        CapabilityChecks.optionMarks(adapter, listener, optionCode, pushTimeoutMs)
    }

    @Test
    fun `optional histories answer in shape when declared and are refused as unsupported when not`(
        @TempDir dir: Path,
    ) {
        val (adapter, _) = connect(dir)
        val toMs = System.currentTimeMillis()
        val fromMs = toMs - HISTORY_CHECKED_MS

        CapabilityChecks.settlements(adapter, fromMs, toMs)
        CapabilityChecks.funding(adapter, fromMs, toMs)
        CapabilityChecks.fundingRates(adapter, perpetualCode, fromMs, toMs)
        CapabilityChecks.marks(adapter, activeCode, barWindowMs)
        CapabilityChecks.openInterest(adapter, activeCode, fromMs, toMs + CLOCK_SKEW_MS)
    }

    @Test
    fun `a working order is still found by its label after a restart`(
        @TempDir dir: Path,
    ) {
        val (first, _) = connect(dir)
        val id = label()
        first.placeTracked(restingOrder(id))
        first.close()
        adapters.remove(first)

        val (restarted, _) = connect(dir)

        assertThat(restarted.orderByLabel(id)?.status).isEqualTo(OrderStatus.WORKING)
        assertThat(restarted.openOrders().map { it.clientOrderId }).contains(id)
    }

    private companion object {
        const val CLOCK_SKEW_MS = 60_000L
        const val HISTORY_CHECKED_MS = 2 * 86_400_000L
    }
}

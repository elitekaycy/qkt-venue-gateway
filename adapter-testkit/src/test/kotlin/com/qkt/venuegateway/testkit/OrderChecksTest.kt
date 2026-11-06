package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueSettlement
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OrderChecksTest {
    private val kit = KitOrders(pushTimeoutMs = 50)
    private val market =
        NewOrder("m-1", PERP, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.IOC)
    private val limit = market.copy(clientOrderId = "l-1", type = OrderType.LIMIT, limitPrice = BigDecimal("50"))

    private fun connected(venue: FakeVenue) = RecordingListener().also(venue::connect)

    @Test
    fun `labels are unique within a run and name it`() {
        val first = kit.label()
        val second = kit.label()

        assertThat(first).startsWith("kit-").endsWith("-1")
        assertThat(second).isEqualTo(first.removeSuffix("1") + "2")
    }

    @Test
    fun `a resting sell moves a tick up and reads back, and a change the venue cannot make fails`() {
        val venue = FakeVenue()
        val sell = limit.copy(side = Side.SELL, limitPrice = BigDecimal("150"))
        kit.place(venue, sell)

        kit.moveAndReadBack(venue, sell)

        assertThat(venue.orderByLabel("l-1")?.limitPrice).isEqualByComparingTo("150.5")
        assertThatThrownBy { kit.moveAndReadBack(venue, limit.copy(clientOrderId = "never-placed")) }
            .isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `a fill waits for its pushes and fails naming the order when they never add up`() {
        val venue = FakeVenue()
        val listener = connected(venue)

        kit.fill(venue, listener, market)
        venue.marketRests = true

        assertThat(venue.orderByLabel("m-1")?.status).isEqualTo(OrderStatus.FILLED)
        assertThatThrownBy { kit.fill(venue, listener, market.copy(clientOrderId = "m-2")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("timed out waiting for fills of m-2 adding up to 1")
    }

    @Test
    fun `leaving nothing working cancels only what still works, whatever became of the rest`() {
        val venue = FakeVenue()
        kit.place(venue, limit)
        kit.place(venue, market)
        assertThatThrownBy { kit.place(venue, limit.copy(clientOrderId = "r-1", quantity = BigDecimal("0.01"))) }
            .isInstanceOf(VenueRefusedException::class.java)

        kit.cancelWorking(venue)

        assertThat(venue.orderByLabel("l-1")?.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(venue.orderByLabel("m-1")?.status).isEqualTo(OrderStatus.FILLED)
        assertThat(venue.orderByLabel("r-1")).isNull()
    }

    @Test
    fun `a market order larger than the book is closed in as many reduce-only orders as the book allows`() {
        val venue =
            FakeVenue().apply {
                depth = BigDecimal("10")
                closeDepth = BigDecimal.ONE
            }
        val overrun = market.copy(quantity = BigDecimal("15"))

        RemainderChecks.overrun(venue, kit, overrun, kit::label, 1_000)

        assertThat(venue.orderByLabel("m-1")?.filledQuantity).isEqualByComparingTo("10")
        val orders = venue.book.orders.values
        assertThat(orders.filter { it.reduceOnly && it.side == Side.SELL }).hasSize(10)
        assertThat(ContractChecks.net(venue.positions(), PERP)).isZero
    }

    @Test
    fun `a market order with a part still working fails naming it`() {
        val venue = FakeVenue().apply { marketRests = true }

        assertThatThrownBy { RemainderChecks.overrun(venue, kit, market, kit::label, 1) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageStartingWith("market order m-1 still has a part working")
    }

    @Test
    fun `the listener records every push by kind, in arrival order`() {
        val listener = RecordingListener()
        val fill = VenueFill("a", null, "f", PERP, Side.BUY, BigDecimal.ONE, PRICE, 1L)
        val settlement = VenueSettlement(FUTURE, PRICE, 2L)
        val funding = VenueFunding("fu", PERP, BigDecimal.ONE, "USD", null, 3L)
        val quote = VenueQuote(PERP, timeMs = 4L)

        listener.fill(fill)
        listener.settlement(settlement)
        listener.funding(funding)
        listener.quote(quote)
        listener.quoteFeed(false, "ticker down")
        listener.quoteFeed(true, "ticker up")
        listener.connection(true, "link up")

        assertThat(listener.fills).containsExactly(fill)
        assertThat(listener.settlements).containsExactly(settlement)
        assertThat(listener.funding).containsExactly(funding)
        assertThat(listener.quotes).containsExactly(quote)
        assertThat(listener.quoteFeed).containsExactly(false, true)
        assertThat(listener.connections).containsExactly(true)
    }

    @Test
    fun `awaiting returns once its condition holds and otherwise fails naming what it waited for`() {
        val listener = RecordingListener()
        var polls = 0

        listener.await("the third poll") { ++polls >= 3 }

        assertThat(polls).isEqualTo(3)
        assertThatThrownBy { listener.await("nothing", 30) { false } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("timed out waiting for nothing")
    }
}

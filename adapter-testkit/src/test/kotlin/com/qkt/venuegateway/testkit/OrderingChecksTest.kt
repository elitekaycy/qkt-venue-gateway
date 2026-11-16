package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

/** Every history must come oldest first, and the order checks hold whichever side the order is on. */
class OrderingChecksTest {
    private val from = 1_800_000_000L
    private val to = from + 3_600_000L
    private val minute = 60_000L
    private val market =
        NewOrder("m-1", PERP, Side.SELL, OrderType.MARKET, BigDecimal("5"), timeInForce = TimeInForce.IOC)

    private fun fails(
        check: ThrowingCallable,
        naming: String,
    ) {
        assertThatThrownBy(check).isInstanceOf(AssertionError::class.java).hasMessageContaining(naming)
    }

    /** A venue answering every history newest first. */
    private val backwards =
        object : FakeVenue() {
            override fun funding(
                fromMs: Long,
                toMs: Long,
            ) = super.funding(fromMs, toMs).reversed()

            override fun marks(
                code: String,
                windowMs: Long,
                fromMs: Long,
                toMs: Long,
            ) = super.marks(code, windowMs, fromMs, toMs).reversed()

            override fun openInterest(
                code: String,
                fromMs: Long,
                toMs: Long,
            ) = super.openInterest(code, fromMs, toMs).reversed()

            override fun settlements(
                fromMs: Long,
                toMs: Long,
            ) = super.settlements(fromMs, toMs).reversed()

            override fun trades(
                code: String,
                fromMs: Long,
                toMs: Long,
                limit: Int,
            ) = super.trades(code, fromMs, toMs, limit).reversed()

            override fun depth(
                code: String,
                fromMs: Long,
                toMs: Long,
            ) = listOf(VenueDepth(toMs - 1, emptyList(), listOf(level("102"), level("101"))))
        }

    private fun level(price: String) = VenueLevel(BigDecimal(price), BigDecimal.ONE)

    @Test
    fun `a history answered newest first fails naming the history`() {
        fails({ CapabilityChecks.funding(backwards, from, to) }, "funding ascends")
        fails({ CapabilityChecks.marks(backwards, PERP, minute) }, "marks ascend")
        fails({ CapabilityChecks.openInterest(backwards, PERP, from, to) }, "open interest ascends")
        fails({ CapabilityChecks.settlements(backwards, from, to) }, "settlements ascend")
        fails({ TapeChecks.tape(backwards, PERP, to) }, "trades ascend")
        fails({ DepthChecks.depth(backwards, PERP, from, to) }, "asks at ${to - 1}: order")
    }

    @Test
    fun `undeclared funding rates are refused whichever perpetual is named`() {
        CapabilityChecks.fundingRates(FakeVenue().apply { capabilities = emptySet() }, PERP, from, to)
    }

    @Test
    fun `a quote with only its bid passes, and option marks wait past quotes without a mark IV`() {
        val venue =
            object : FakeVenue() {
                override fun subscribeQuotes(codes: Set<String>) {
                    listener?.quote(VenueQuote(PERP, bid = PRICE, timeMs = 1L))
                    listener?.quote(VenueQuote(OPTION, mark = PRICE, timeMs = 1L))
                    listener?.quote(VenueQuote(OPTION, markIv = BigDecimal("50"), timeMs = 2L))
                    listener?.quote(VenueQuote(OPTION, markIv = BigDecimal("50"), underlying = PRICE, timeMs = 3L))
                }
            }
        val listener = RecordingListener().also(venue::connect)

        CapabilityChecks.barsAndQuotes(venue, listener, PERP, minute, 100)
        CapabilityChecks.optionMarks(venue, listener, OPTION, 100)

        assertThat(venue.capabilities).contains(Capability.OPTION_MARKS)
    }

    @Test
    fun `a sell larger than the book is bought back to flat`() {
        val venue = FakeVenue().apply { depth = BigDecimal("2") }

        RemainderChecks.overrun(venue, KitOrders(50), market, { "close-1" }, 1_000)

        assertThat(venue.orderByLabel("close-1")?.side).isEqualTo(Side.BUY)
        assertThat(ContractChecks.net(venue.positions(), PERP)).isZero
    }

    @Test
    fun `a venue that loses the order it took fails the remainder, fill and change checks`() {
        val forgetful =
            object : FakeVenue() {
                override fun orderByLabel(clientOrderId: String): VenueOrder? = null
            }
        val kit = KitOrders(50)
        val resting = market.copy(clientOrderId = "r-1", type = OrderType.LIMIT, limitPrice = PRICE)

        assertThatThrownBy { RemainderChecks.overrun(forgetful, kit, market, { "x" }, 1) }
            .hasMessage("market order m-1 still has a part working: null")
        val listener = RecordingListener().also(forgetful::connect)
        fails({ kit.fill(forgetful, listener, market.copy(clientOrderId = "m-2")) }, "status of m-2")
        kit.place(forgetful, resting)
        fails({ kit.moveAndReadBack(forgetful, resting) }, "limit of r-1 read back")
    }
}

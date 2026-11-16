package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenuePrint
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

class ShapeChecksTest {
    private val from = 1_800_000_000L
    private val to = from + 3_600_000L
    private val listing = FakeVenue().instruments()

    private fun fails(
        check: ThrowingCallable,
        naming: String,
    ) {
        assertThatThrownBy(check).isInstanceOf(AssertionError::class.java).hasMessageContaining(naming)
    }

    private fun level(price: String) = VenueLevel(BigDecimal(price), BigDecimal.ONE)

    private fun bar(
        startMs: Long,
        high: String = "101",
    ) = VenueBar(startMs, PRICE, BigDecimal(high), BigDecimal("99"), PRICE, BigDecimal.ONE)

    private fun books(vararg snapshots: VenueDepth) =
        object : FakeVenue() {
            override fun depth(
                code: String,
                fromMs: Long,
                toMs: Long,
            ) = snapshots.toList()
        }

    private fun depth(vararg book: VenueDepth) = DepthChecks.depth(books(*book), PERP, from, to)

    private fun tape(vararg prints: VenuePrint) =
        object : FakeVenue() {
            override fun trades(
                code: String,
                fromMs: Long,
                toMs: Long,
                limit: Int,
            ) = prints.toList()
        }

    @Test
    fun `a listing passes with each kind's fields and fails empty, repeated or an option without its strike`() {
        ContractChecks.listing(listing)

        fails({ ContractChecks.listing(emptyList()) }, "the venue lists instruments")
        fails({ ContractChecks.listing(listing + listing.first()) }, "instrument codes")
        fails({ ContractChecks.listing(listing.map { it.copy(strike = null) }) }, "instrument $OPTION strike")
        fails({ ContractChecks.listing(listing.map { it.copy(right = "call") }) }, "instrument $PERP right")
    }

    @Test
    fun `klines pass on their boundaries and fail empty, off a boundary or with a high below the body`() {
        val minute = 60_000L

        ContractChecks.klines(listOf(bar(from), bar(from + minute)), minute, from, to)

        fails({ ContractChecks.klines(emptyList(), minute, from, to) }, "klines over the last window")
        fails({ ContractChecks.klines(listOf(bar(from + minute), bar(from)), minute, from, to) }, "klines ascend")
        fails({ ContractChecks.klines(listOf(bar(from + 1)), minute, from, to) }, "on its boundary")
        fails({ ContractChecks.klines(listOf(bar(from, high = "99.5")), minute, from, to) }, "high of $from")
    }

    @Test
    fun `a net position adds every row of its symbol, signed by side`() {
        val rows =
            listOf(
                PositionRow(PERP, BigDecimal("2"), PRICE, "t-1"),
                PositionRow(PERP, BigDecimal("-0.5"), PRICE, "t-2"),
            )
        val held = Positions(Accounting.HEDGING, rows + PositionRow(FUTURE, BigDecimal.ONE, PRICE))

        assertThat(ContractChecks.net(held, PERP)).isEqualByComparingTo("1.5")
        assertThat(ContractChecks.net(held, OPTION)).isZero
        assertThat(ContractChecks.signed(Side.BUY, BigDecimal.ONE)).isEqualByComparingTo("1")
        assertThat(ContractChecks.signed(Side.SELL, BigDecimal.ONE)).isEqualByComparingTo("-1")
    }

    @Test
    fun `depth passes a one-sided book and fails crossed, out of order, too deep or with nothing present`() {
        val deep = (1..11).map { level("${100 - it}") }
        val empty = emptyList<VenueLevel>()

        depth(VenueDepth(from, empty, listOf(level("101"))))

        fails({ depth() }, "depth of $PERP up to now")
        fails({ depth(VenueDepth(from, listOf(level("101")), listOf(level("100")))) }, "best bid")
        fails({ depth(VenueDepth(from, listOf(level("98"), level("99")), empty)) }, "order")
        fails({ depth(VenueDepth(from, deep, empty)) }, "levels")
        fails({ depth(VenueDepth(from, empty, empty)) }, "the present book")
        fails({ depth(VenueDepth(to, empty, empty), VenueDepth(from, empty, empty)) }, "depth ascends")
    }

    @Test
    fun `the tape fails with no recent trade, a repeated id, or a print outside its hour or at no price`() {
        val now = to
        val start = now - 3_600_000L
        val print = VenuePrint("t-1", start, PRICE, BigDecimal.ONE, Side.BUY)

        TapeChecks.tape(tape(print), PERP, now)

        fails({ TapeChecks.tape(tape(), PERP, now) }, "trades of $PERP over the last hour")
        fails({ TapeChecks.tape(tape(print, print), PERP, now) }, "trade ids")
        fails({ TapeChecks.tape(tape(print.copy(timeMs = now)), PERP, now) }, "trade t-1 time")
        fails({ TapeChecks.tape(tape(print.copy(price = BigDecimal.ZERO)), PERP, now) }, "trade t-1 price")
        fails({ TapeChecks.tape(tape(print.copy(size = BigDecimal.ZERO)), PERP, now) }, "trade t-1 size")
    }

    @Test
    fun `liquidations may be none, but never one side twice under one id`() {
        val quiet =
            object : FakeVenue() {
                override fun liquidations(
                    code: String,
                    fromMs: Long,
                    toMs: Long,
                ) = emptyList<VenuePrint>()
            }
        val twice =
            object : FakeVenue() {
                override fun liquidations(
                    code: String,
                    fromMs: Long,
                    toMs: Long,
                ) = super.liquidations(code, fromMs, toMs).map { it.copy(side = Side.SELL) }
            }

        TapeChecks.tape(quiet, PERP, to)
        fails({ TapeChecks.tape(twice, PERP, to) }, "liquidation ids and sides")
    }
}

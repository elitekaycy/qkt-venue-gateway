package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal const val PERP = "FAKE-PERPETUAL"
internal const val OPTION = "FAKE-1JAN30-100-C"
internal const val FUTURE = "FAKE-1JAN30"
internal val PRICE = BigDecimal("100")
internal val STEP = BigDecimal("0.1")

/** What a fake venue account holds; two adapters on one book are one account before and after a restart. */
internal class FakeBook {
    val orders = ConcurrentHashMap<String, VenueOrder>()
    val fills = CopyOnWriteArrayList<VenueFill>()
    val net = ConcurrentHashMap<String, BigDecimal>()
    val fillIds = AtomicInteger()
}

/**
 * The optional histories of [FakeVenue], each answering in shape inside the asked window when declared in
 * [capabilities] and refused as unsupported when not (unless [answersUndeclared]).
 */
internal abstract class FakeHistories : VenueAdapter {
    override var capabilities: Set<Capability> = Capability.entries.toSet()

    /** Answers what it does not declare instead of refusing it. */
    var answersUndeclared = false

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.SETTLEMENTS, "settlements") {
        listOf(VenueSettlement(FUTURE, PRICE, fromMs + 1), VenueSettlement(FUTURE, PRICE, fromMs + 2))
    }

    override fun funding(
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.FUNDING, "funding") {
        listOf(
            VenueFunding("fu-1", PERP, BigDecimal("0.5"), "USD", BigDecimal.ONE, fromMs + 1),
            VenueFunding("fu-2", PERP, BigDecimal("-0.25"), "USD", null, fromMs + 2),
        )
    }

    override fun fundingRates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.FUNDING_RATES, "funding rates") {
        listOf(
            VenueFundingRate(fromMs + 1, BigDecimal("0.0001"), PRICE),
            VenueFundingRate(fromMs + 2, BigDecimal("-0.0001"), null),
        )
    }

    override fun marks(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.MARK_PRICES, "mark prices") {
        val first = (fromMs + windowMs - 1) / windowMs * windowMs
        listOf(
            VenueMark(first, PRICE, PRICE),
            VenueMark(first + windowMs, null, PRICE),
            VenueMark(first + 2 * windowMs, PRICE, null),
        )
    }

    override fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.OPEN_INTEREST, "open interest") {
        listOf(VenueOpenInterest(fromMs + 1, BigDecimal("5")), VenueOpenInterest(toMs - 1, BigDecimal.ZERO))
    }

    override fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ) = serving(Capability.TRADES, "trades") {
        (1..3).map { VenuePrint("t-$it", fromMs + it, PRICE, STEP, Side.BUY) }
    }

    override fun liquidations(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.LIQUIDATIONS, "liquidations") {
        listOf(
            VenuePrint("l-1", fromMs + 1, PRICE, STEP, Side.SELL),
            VenuePrint("l-1", fromMs + 1, PRICE, STEP, Side.BUY),
        )
    }

    override fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = serving(Capability.DEPTH, "depth") {
        listOf(
            VenueDepth(fromMs + 1, listOf(level("99")), emptyList()),
            VenueDepth(toMs - 1, listOf(level("99"), level("98")), listOf(level("101"), level("102"))),
        )
    }

    private fun <T> serving(
        capability: Capability,
        what: String,
        answer: () -> T,
    ): T = if (capability in capabilities || answersUndeclared) answer() else throw VenueUnsupportedException(what)

    private fun level(price: String) = VenueLevel(BigDecimal(price), BigDecimal.ONE)
}

package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.Test

class CapabilityChecksTest {
    private val from = 1_000_000L
    private val to = from + 86_400_000L
    private val minute = 60_000L
    private val unanswered = "Expecting code to raise a throwable"

    private fun fails(
        check: ThrowingCallable,
        naming: String,
    ) {
        assertThatThrownBy(check).isInstanceOf(AssertionError::class.java).hasMessageContaining(naming)
    }

    private fun connected(venue: FakeVenue) = RecordingListener().also(venue::connect)

    @Test
    fun `a venue declaring nothing optional passes by refusing every history, and fails when it answers one`() {
        val venue = FakeVenue().apply { capabilities = emptySet() }

        CapabilityChecks.histories(venue, PERP, null, minute, 0)
        venue.answersUndeclared = true

        fails({ CapabilityChecks.histories(venue, PERP, null, minute, 0) }, unanswered)
    }

    @Test
    fun `undeclared funding, funding rates, marks, open interest and the tape each fail when answered anyway`() {
        val venue =
            FakeVenue().apply {
                capabilities = emptySet()
                answersUndeclared = true
            }

        fails({ CapabilityChecks.funding(venue, from, to) }, unanswered)
        fails({ CapabilityChecks.fundingRates(venue, null, from, to) }, unanswered)
        fails({ CapabilityChecks.marks(venue, PERP, minute) }, unanswered)
        fails({ CapabilityChecks.openInterest(venue, PERP, from, to) }, unanswered)
        fails({ TapeChecks.tape(venue, PERP, to) }, unanswered)
        fails({ DepthChecks.depth(venue, PERP, from, to) }, unanswered)
    }

    @Test
    fun `funding fails on a repeated id or a record of nothing`() {
        val repeated =
            object : FakeVenue() {
                override fun funding(
                    fromMs: Long,
                    toMs: Long,
                ) = super.funding(fromMs, toMs).map { it.copy(fundingId = "same") }
            }
        val nothing =
            object : FakeVenue() {
                override fun funding(
                    fromMs: Long,
                    toMs: Long,
                ) = listOf(VenueFunding("f", PERP, BigDecimal.ZERO, "USD", null, fromMs))
            }

        fails({ CapabilityChecks.funding(repeated, from, to) }, "funding ids")
        fails({ CapabilityChecks.funding(nothing, from, to) }, "funding f amount")
    }

    @Test
    fun `funding rates fail without a perpetual to read, or when they do not ascend`() {
        val backwards =
            object : FakeVenue() {
                override fun fundingRates(
                    code: String,
                    fromMs: Long,
                    toMs: Long,
                ) = super.fundingRates(code, fromMs, toMs).reversed()
            }
        val priceless =
            object : FakeVenue() {
                override fun fundingRates(
                    code: String,
                    fromMs: Long,
                    toMs: Long,
                ) = listOf(VenueFundingRate(fromMs, BigDecimal.ONE, BigDecimal.ZERO))
            }

        fails({ CapabilityChecks.fundingRates(FakeVenue(), null, from, to) }, "perpetualCode")
        fails({ CapabilityChecks.fundingRates(backwards, PERP, from, to) }, "rates ascend")
        fails({ CapabilityChecks.fundingRates(priceless, PERP, from, to) }, "rate price")
    }

    @Test
    fun `marks fail on two in one window or a window reporting neither mark nor index`() {
        val twice =
            object : FakeVenue() {
                override fun marks(
                    code: String,
                    windowMs: Long,
                    fromMs: Long,
                    toMs: Long,
                ) = super.marks(code, windowMs, fromMs, toMs).let { it + it.last().copy(mark = PRICE) }
            }
        val blank =
            object : FakeVenue() {
                override fun marks(
                    code: String,
                    windowMs: Long,
                    fromMs: Long,
                    toMs: Long,
                ) = listOf(VenueMark(fromMs, null, null))
            }

        fails({ CapabilityChecks.marks(twice, PERP, minute) }, "one mark per window")
        fails({ CapabilityChecks.marks(blank, PERP, minute) }, "mark or index")
    }

    @Test
    fun `open interest fails when the venue has no figure up to now, or a negative one`() {
        val none =
            object : FakeVenue() {
                override fun openInterest(
                    code: String,
                    fromMs: Long,
                    toMs: Long,
                ) = emptyList<VenueOpenInterest>()
            }
        val negative =
            object : FakeVenue() {
                override fun openInterest(
                    code: String,
                    fromMs: Long,
                    toMs: Long,
                ) = listOf(VenueOpenInterest(fromMs, BigDecimal("-1")))
            }

        fails({ CapabilityChecks.openInterest(none, PERP, from, to) }, "up to now")
        fails({ CapabilityChecks.openInterest(negative, PERP, from, to) }, "open interest at $from")
    }

    @Test
    fun `settlements fail outside their window`() {
        val late =
            object : FakeVenue() {
                override fun settlements(
                    fromMs: Long,
                    toMs: Long,
                ) = listOf(VenueSettlement(FUTURE, PRICE, toMs + 1))
            }

        fails({ CapabilityChecks.settlements(late, from, to) }, "settlement of $FUTURE")
    }

    @Test
    fun `bars need a minute kline, and a quote passes with one side missing but fails crossed`() {
        val hourly =
            object : FakeVenue() {
                override val klineResolutions = setOf(3_600_000L)
            }
        val oneSided =
            object : FakeVenue() {
                override fun subscribeQuotes(codes: Set<String>) {
                    listener?.quote(VenueQuote(PERP, ask = PRICE, timeMs = 1L))
                }
            }
        val crossed =
            object : FakeVenue() {
                override fun subscribeQuotes(codes: Set<String>) {
                    listener?.quote(VenueQuote(PERP, bid = PRICE, ask = BigDecimal.ONE, timeMs = 1L))
                }
            }

        fails({ CapabilityChecks.barsAndQuotes(hourly, connected(hourly), PERP, minute, 100) }, "kline resolutions")
        CapabilityChecks.barsAndQuotes(oneSided, connected(oneSided), PERP, minute, 100)
        fails({ CapabilityChecks.barsAndQuotes(crossed, connected(crossed), PERP, minute, 100) }, "less than or equal")
    }

    @Test
    fun `option marks ride on quotes, need an option to quote, and a quote that never comes times out`() {
        val noQuotes = FakeVenue().apply { capabilities = setOf(Capability.OPTION_MARKS) }
        val silent =
            object : FakeVenue() {
                override fun subscribeQuotes(codes: Set<String>) = Unit
            }

        fails({ CapabilityChecks.optionMarks(noQuotes, connected(noQuotes), OPTION, 100) }, "ride on quotes")
        fails({ CapabilityChecks.optionMarks(FakeVenue(), RecordingListener(), null, 100) }, "optionCode")
        assertThatThrownBy { CapabilityChecks.optionMarks(silent, connected(silent), OPTION, 50) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("timed out waiting for a quote of $OPTION with its mark IV and forward")
    }
}

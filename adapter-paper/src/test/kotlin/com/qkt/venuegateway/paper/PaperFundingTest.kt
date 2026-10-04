package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.deribit.DeribitListing
import com.qkt.venuegateway.deribit.client.DeribitFundingRate
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperFundingTest {
    private val hour = 3_600_000L
    private val perp = "SOL_USDC-PERPETUAL"
    private val future = "SOL_USDC-27DEC26"
    private var now = 1_000 * hour + 1_800_000
    private val published = mutableListOf<DeribitFundingRate>()
    private var unreachable = false

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = if (kind == "future") listOf(listed(perp, null), listed(future, 2_000 * hour)) else emptyList()

            override fun instrument(name: String) = instruments("USDC", "future").first { it.name == name }

            override fun ticker(name: String): DeribitTicker = error("unused")

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ): List<DeribitKline> = error("unused")

            override fun fundingRates(
                name: String,
                fromMs: Long,
                toMs: Long,
            ): List<DeribitFundingRate> {
                if (unreachable) throw IOException("deribit down")
                return published.filter { it.timestampMs in fromMs..toMs }
            }

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    private fun listed(
        name: String,
        expiryMs: Long?,
    ) = DeribitInstrument(
        name,
        "future",
        expiryMs == null,
        expiryMs,
        null,
        null,
        BigDecimal("0.1"),
        BigDecimal("0.001"),
        BigDecimal("0.1"),
        "USDC",
        "sol_usdc",
    )

    private val funding = PaperFunding(market, DeribitListing(market, "USDC", { now }), "USDC") { now }

    private fun book(): PaperBook = PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal.ZERO)

    private fun rate(
        hours: Long,
        rate: String,
    ) = DeribitFundingRate(hours * hour, BigDecimal(rate), BigDecimal("121.5"))

    @Test
    fun `a held perpetual pays each hour published after it was first seen, a long the rate, a short paid it`() {
        val long = book().apply { ledger.apply(perp, Side.BUY, BigDecimal("150"), BigDecimal("120")) }
        val short = book().apply { ledger.apply(perp, Side.SELL, BigDecimal("150"), BigDecimal("120")) }
        published += rate(1_000, "0.0004")
        assertThat(funding.due(long) + funding.due(short)).isEmpty()

        published += rate(1_001, "0.00004182650335544141")
        published += rate(1_002, "0")
        now = 1_002 * hour + 600_000
        val charged = funding.due(long)
        val credited = funding.due(short)

        assertThat(charged.map { it.amount.toPlainString() }).containsExactly("0.76228802")
        assertThat(charged.single().fundingId).isEqualTo("paper-$perp-${1_001 * hour}")
        assertThat(charged.single().position).isEqualByComparingTo("150")
        assertThat(long.ledger.balance).isEqualByComparingTo("9999.23771198")
        assertThat(credited.map { it.amount.toPlainString() }).containsExactly("-0.76228802")
        assertThat(short.ledger.balance).isEqualByComparingTo("10000.76228802")
        assertThat(funding.due(long)).isEmpty()
    }

    @Test
    fun `a rate Deribit publishes late is charged once it appears, and a dated future pays none`() {
        val book =
            book().apply {
                ledger.apply(perp, Side.BUY, BigDecimal("10"), BigDecimal("120"))
                ledger.apply(future, Side.BUY, BigDecimal("10"), BigDecimal("120"))
            }
        funding.due(book)
        now = 1_001 * hour + 60_000
        assertThat(funding.due(book)).isEmpty()

        published += rate(1_001, "0.001")
        now = 1_001 * hour + 2_400_000

        assertThat(funding.due(book).map { it.symbol to it.amount.toPlainString() }).containsExactly(
            perp to "1.21500000",
        )
        assertThat(book.funding.chargedThrough.keys).containsExactly(perp)
    }

    @Test
    fun `a failed rate read charges nothing, and a restart keeps what was charged and from when`(
        @TempDir dir: Path,
    ) {
        val book = book().apply { ledger.apply(perp, Side.BUY, BigDecimal("10"), BigDecimal("120")) }
        funding.due(book)
        published += rate(1_001, "0.001")
        now = 1_001 * hour + 2_400_000
        unreachable = true

        assertThatThrownBy { funding.due(book) }.isInstanceOf(IOException::class.java)
        assertThat(book.ledger.balance).isEqualByComparingTo("10000")

        unreachable = false
        funding.due(book)
        PaperStore(dir).save(book)
        val restored = book().also { PaperStore(dir).load(it) }

        assertThat(restored.funding.records.map { it.amount.toPlainString() }).containsExactly("1.21500000")
        assertThat(restored.ledger.balance).isEqualByComparingTo("9998.785")
        assertThat(funding.due(restored)).isEmpty()
    }
}

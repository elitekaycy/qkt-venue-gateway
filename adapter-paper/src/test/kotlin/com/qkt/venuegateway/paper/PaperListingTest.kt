package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperListingTest {
    private fun instrument(
        name: String,
        kind: String,
        contractSize: String,
        min: String,
    ) = DeribitInstrument(
        name,
        kind,
        name.endsWith("PERPETUAL"),
        null,
        null,
        null,
        BigDecimal(contractSize),
        BigDecimal("0.1"),
        BigDecimal(min),
        "USDC",
        "x",
    )

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = if (kind == "future") {
                listOf(instrument("BTC_USDC-PERPETUAL", "future", "0.0001", "0.0001"))
            } else {
                listOf(instrument("AVAX_USDC-2OCT26-5-C", "option", "100", "100"))
            }

            override fun instrument(name: String) = error("not used")

            override fun ticker(name: String): DeribitTicker = error("not used")

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ) = emptyList<DeribitKline>()

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    @Test
    fun `linear contracts are listed in coins, with contract size 1 and the venue contract size as volume step`() {
        val listed = PaperListing(market, "USDC", { 0L }).let { l -> l.all().map(l::neutral) }.associateBy { it.code }

        val perp = listed.getValue("BTC_USDC-PERPETUAL")
        assertThat(perp.kind).isEqualTo(InstrumentKind.PERPETUAL)
        assertThat(perp.contractSize).isEqualByComparingTo("1")
        assertThat(perp.volumeStep).isEqualByComparingTo("0.0001")
        val option = listed.getValue("AVAX_USDC-2OCT26-5-C")
        assertThat(option.contractSize).isEqualByComparingTo("1")
        assertThat(option.volumeStep).isEqualByComparingTo("100")
        assertThat(option.underlying).isEqualTo("AVAX_USDC")
    }
}

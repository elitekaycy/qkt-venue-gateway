package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitMarketMappingTest {
    @Test
    fun `a ticker becomes a quote with every value Deribit reported and none it did not`() {
        val ticker =
            DeribitTicker(
                "BTC_USDC-2OCT26-82000-P",
                7,
                BigDecimal("100"),
                BigDecimal("2"),
                null,
                null,
                BigDecimal("101"),
                BigDecimal("48.5"),
                BigDecimal("83000"),
                null,
            )

        val quote = DeribitMarketMapping.quote(ticker)

        assertThat(quote.symbol).isEqualTo("BTC_USDC-2OCT26-82000-P")
        assertThat(quote.timeMs).isEqualTo(7)
        assertThat(quote.bid).isEqualByComparingTo("100")
        assertThat(quote.bidSize).isEqualByComparingTo("2")
        assertThat(quote.ask).isNull()
        assertThat(quote.askSize).isNull()
        assertThat(quote.mark).isEqualByComparingTo("101")
        assertThat(quote.markIv).isEqualByComparingTo("48.5")
        assertThat(quote.underlying).isEqualByComparingTo("83000")
        assertThat(quote.index).isNull()
    }

    @Test
    fun `a recorded option ticker's index, which Deribit charges fees on, rides beside its forward`() {
        val recorded = javaClass.getResource("/fixtures/ticker-option.json")!!.readText()
        val ticker =
            DeribitJson.ticker(
                Json
                    .parseToJsonElement(recorded)
                    .jsonObject
                    .getValue("result")
                    .jsonObject,
            )

        val quote = DeribitMarketMapping.quote(ticker)

        assertThat(quote.index).isEqualByComparingTo("83975.68")
        assertThat(quote.underlying).isEqualByComparingTo("83987.8")
    }

    @Test
    fun `a recorded option ticker's mark iv and forward, what option marks declares, reach its quote exactly`() {
        val recorded = javaClass.getResource("/fixtures/ticker-option-greeks.json")!!.readText()
        val ticker =
            DeribitJson.ticker(
                Json
                    .parseToJsonElement(recorded)
                    .jsonObject
                    .getValue("result")
                    .jsonObject,
            )

        val quote = DeribitMarketMapping.quote(ticker)

        assertThat(quote.symbol).isEqualTo("BTC_USDC-30OCT26-110000-C")
        assertThat(quote.markIv).isEqualByComparingTo("45.46")
        assertThat(quote.underlying).isEqualByComparingTo("86575.5")
        assertThat(quote.mark).isEqualByComparingTo("99.04")
    }
}

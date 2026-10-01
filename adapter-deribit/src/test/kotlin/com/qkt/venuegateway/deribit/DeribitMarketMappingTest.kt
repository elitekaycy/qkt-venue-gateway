package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
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
    }
}

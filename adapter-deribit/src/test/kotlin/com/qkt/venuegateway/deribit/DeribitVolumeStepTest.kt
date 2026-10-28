package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.market.DeribitMarketMapping
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Sizes as Deribit testnet listed them on 2026-10-19 (`public/get_instruments`). */
class DeribitVolumeStepTest {
    @ParameterizedTest(name = "{0}: contract size {1}, minimum {2}")
    @CsvSource(
        "BTC_USDC-PERPETUAL, future, 0.0001, 0.0001, USDC",
        "AVAX_USDC-20OCT26, future, 0.1, 0.1, USDC",
        "BTC_USDC-23OCT26-82000-C, option, 1, 0.01, USDC",
        "AVAX_USDC-23OCT26-12-C, option, 100, 100, USDC",
        "BTC-PERPETUAL, future, 10, 10, BTC",
        "BTC-23OCT26-82000-C, option, 1, 0.1, BTC",
    )
    fun `every instrument steps in its minimum trade amount, so the minimum itself is a valid quantity`(
        name: String,
        kind: String,
        contractSize: String,
        min: String,
        settlement: String,
    ) {
        val listed =
            DeribitMarketMapping.instrument(
                DeribitInstrument(
                    name,
                    kind,
                    name.endsWith("PERPETUAL"),
                    null,
                    null,
                    null,
                    BigDecimal(contractSize),
                    BigDecimal("0.5"),
                    BigDecimal(min),
                    settlement,
                    "x",
                ),
            )

        assertThat(listed.volumeStep).isEqualByComparingTo(min)
        assertThat(listed.volumeMin).isEqualByComparingTo(min)
        assertThat(listed.volumeMin.remainder(listed.volumeStep)).isEqualByComparingTo("0")
    }
}

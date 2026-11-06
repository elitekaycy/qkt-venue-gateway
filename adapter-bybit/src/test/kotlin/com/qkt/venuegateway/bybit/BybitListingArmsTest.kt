package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitInstrument
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitListingArmsTest {
    private val btc: BybitInstrument =
        BybitMarketJson.instrument(
            firstRecord("instruments-linear-BTCUSDT.json"),
            "linear",
        )

    @Test
    fun `only a trading instrument settled in the account's coin is listed, by symbol`() {
        val all =
            listOf(
                btc.copy(symbol = "ZZZUSDT"),
                btc.copy(symbol = "AAAUSDT", status = "PreLaunch"),
                btc.copy(symbol = "BBBPERP", settleCoin = "USDC"),
                btc,
            )
        val listing = BybitListing({ all }, "USDT", { 0L }, 600_000)

        assertThat(listing.all().map { it.symbol }).containsExactly("BTCUSDT", "ZZZUSDT")
        assertThat(listing.find("BTCUSDT")).isEqualTo(btc)
        assertThat(listing.find("AAAUSDT")).isNull()
        assertThat(listing.find("BBBPERP")).isNull()
    }
}

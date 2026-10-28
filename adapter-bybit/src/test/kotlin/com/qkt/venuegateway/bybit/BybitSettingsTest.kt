package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.TradeMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitSettingsTest {
    private val testnet = mapOf("environment" to "testnet", "category" to "linear")

    @Test
    fun `by default an account trades usdt and stops on the last price`() {
        val s = bybitSettings(testnet)

        assertThat(s.environment).isEqualTo(BybitEnvironment.TESTNET)
        assertThat(s.environment.mode).isEqualTo(TradeMode.DEMO)
        assertThat(s.currency).isEqualTo("USDT")
        assertThat(s.stopTrigger).isEqualTo("LastPrice")
    }

    @Test
    fun `the environment and the category have no default, since each changes what is traded`() {
        assertThatThrownBy { bybitSettings(mapOf("category" to "linear")) }.hasMessageContaining("environment")
        assertThatThrownBy { bybitSettings(mapOf("environment" to "testnet")) }.hasMessageContaining("category")
    }

    @Test
    fun `mainnet is real money`() {
        assertThat(bybitSettings(testnet + ("environment" to "MAINNET")).environment.mode).isEqualTo(TradeMode.REAL)
    }

    @Test
    fun `a bad value is refused by its key`() {
        assertThatThrownBy {
            bybitSettings(
                testnet + ("environment" to "demo"),
            )
        }.hasMessageContaining("setting environment must be one of testnet, mainnet: demo")
        assertThatThrownBy {
            bybitSettings(
                testnet + ("category" to "option"),
            )
        }.hasMessageContaining("category option")
        assertThatThrownBy { bybitSettings(testnet + ("currency" to "BTC")) }.hasMessageContaining("currency BTC")
        assertThatThrownBy {
            bybitSettings(
                testnet + ("stop_trigger" to "bid"),
            )
        }.hasMessageContaining("stop_trigger bid")
    }

    @Test
    fun `spot stops fire on the last price only`() {
        val spot = testnet + ("category" to "spot")

        assertThat(bybitSettings(spot).contracts).isFalse
        assertThatThrownBy { bybitSettings(spot + ("stop_trigger" to "mark_price")) }.hasMessageContaining("spot")
    }

    @Test
    fun `contracts serve their histories and spot only what spot has`() {
        val linear = bybitSettings(testnet + ("stop_trigger" to "mark_price"))
        val spot = bybitSettings(testnet + ("category" to "spot") + ("currency" to "USDC"))

        assertThat(linear.stopTrigger).isEqualTo("MarkPrice")
        assertThat(linear.capabilities).containsExactlyInAnyOrder(
            Capability.BARS,
            Capability.QUOTES,
            Capability.TRADES,
            Capability.DEPTH,
            Capability.FUNDING,
            Capability.FUNDING_RATES,
            Capability.MARK_PRICES,
            Capability.OPEN_INTEREST,
            Capability.LIQUIDATIONS,
        )
        assertThat(
            spot.capabilities,
        ).containsExactlyInAnyOrder(Capability.BARS, Capability.QUOTES, Capability.TRADES, Capability.DEPTH)
        assertThat(linear.capabilities).doesNotContain(Capability.SETTLEMENTS, Capability.OPTION_MARKS)
    }
}

package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.TradeMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BybitSettingsTest {
    private val testnet = mapOf("environment" to "testnet", "category" to "linear")

    @Test
    fun `by default an account trades usdt, stops on the last price and keeps its recordings for ever`() {
        val s = BybitSettings.of(testnet)

        assertThat(s.environment).isEqualTo(BybitEnvironment.TESTNET)
        assertThat(s.environment.mode).isEqualTo(TradeMode.DEMO)
        assertThat(s.currency).isEqualTo("USDT")
        assertThat(s.stopTrigger).isEqualTo("LastPrice")
        assertThat(s.retention).isEqualTo(BybitRetention(0, 0))
    }

    @Test
    fun `the environment and the category have no default, since each changes what is traded`() {
        assertThatThrownBy { BybitSettings.of(mapOf("category" to "linear")) }.hasMessageContaining("environment")
        assertThatThrownBy { BybitSettings.of(mapOf("environment" to "testnet")) }.hasMessageContaining("category")
    }

    @Test
    fun `mainnet is real money`() {
        assertThat(BybitSettings.of(testnet + ("environment" to "MAINNET")).environment.mode).isEqualTo(TradeMode.REAL)
    }

    @Test
    fun `a bad value is refused by its key`() {
        assertThatThrownBy {
            BybitSettings.of(
                testnet + ("environment" to "demo"),
            )
        }.hasMessageContaining("environment demo")
        assertThatThrownBy {
            BybitSettings.of(
                testnet + ("category" to "option"),
            )
        }.hasMessageContaining("category option")
        assertThatThrownBy { BybitSettings.of(testnet + ("currency" to "BTC")) }.hasMessageContaining("currency BTC")
        assertThatThrownBy {
            BybitSettings.of(
                testnet + ("stop_trigger" to "bid"),
            )
        }.hasMessageContaining("stop_trigger bid")
        assertThatThrownBy {
            BybitSettings.of(testnet + ("trades_retention_days" to "3d"))
        }.hasMessageContaining("trades_retention_days")
        assertThatThrownBy {
            BybitSettings.of(testnet + ("depth_retention_days" to "-1"))
        }.hasMessageContaining("depth_retention_days")
    }

    @Test
    fun `spot stops fire on the last price only`() {
        val spot = testnet + ("category" to "spot")

        assertThat(BybitSettings.of(spot).contracts).isFalse
        assertThatThrownBy { BybitSettings.of(spot + ("stop_trigger" to "mark_price")) }.hasMessageContaining("spot")
    }

    @Test
    fun `contracts serve their histories and spot only what spot has`() {
        val linear = BybitSettings.of(testnet + ("stop_trigger" to "mark_price") + ("trades_retention_days" to "7"))
        val spot = BybitSettings.of(testnet + ("category" to "spot") + ("currency" to "USDC"))

        assertThat(linear.stopTrigger).isEqualTo("MarkPrice")
        assertThat(linear.retention.tradesDays).isEqualTo(7)
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

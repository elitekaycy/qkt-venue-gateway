package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.TradeMode
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaSettingsTest {
    private fun of(vararg settings: Pair<String, String>) =
        AlpacaSettings.of(AdapterContext(mapOf(*settings), { 0L }, Path.of("unused")))

    @Test
    fun `the environment has no default, since live is real money`() {
        assertThatThrownBy { of() }.hasMessageContaining("environment").hasMessageContaining("required")
        assertThat(of("environment" to "paper").environment.mode).isEqualTo(TradeMode.DEMO)
        assertThat(of("environment" to "LIVE").environment.mode).isEqualTo(TradeMode.REAL)
    }

    @Test
    fun `defaults are the free feed, regular hours, whole shares, and shares only`() {
        val s = of("environment" to "paper")
        assertThat(s.feed).isEqualTo("iex")
        assertThat(s.extendedHours).isFalse
        assertThat(s.fractional).isFalse
        // Coin pairs are opt-in until qkt ends an order the venue reports filled net of a coin fee (qkt#1515).
        assertThat(s.assetClasses).containsExactly("us_equity")
        assertThat(AlpacaSettings(AlpacaEnvironment.PAPER).assetClasses).containsExactly("us_equity")
        assertThat(of("environment" to "paper", "asset_classes" to "us_equity,crypto").assetClasses)
            .containsExactlyInAnyOrder("us_equity", "crypto")
        assertThat(of("environment" to "paper", "asset_classes" to "crypto").assetClasses).containsExactly("crypto")
    }

    @Test
    fun `a bad value is refused by its key`() {
        fun bad(
            key: String,
            value: String,
        ) = assertThatThrownBy { of("environment" to "paper", key to value) }
        bad("environment", "sandbox").hasMessageContaining("environment").hasMessageContaining("sandbox")
        bad("feed", "otc").hasMessageContaining("feed").hasMessageContaining("otc")
        bad("extended_hours", "yes").hasMessageContaining("extended_hours")
        bad("asset_classes", "options").hasMessageContaining("asset_classes")
    }
}

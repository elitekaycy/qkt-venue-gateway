package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.TradeMode
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class KalshiSettingsTest {
    private val dir = Files.createTempDirectory("kalshi-settings")

    private fun context(
        settings: Map<String, String>,
        credentials: Credentials? = Credentials("key-id", Fixtures.pem),
    ) = AdapterContext(settings, { 0L }, dir, credentials)

    @Test
    fun `the environment has no default, since production is real money; the shard defaults to 0`() {
        assertThatThrownBy { KalshiSettings.of(context(emptyMap())) }
            .hasMessageContaining("setting environment is required")
        val demo = KalshiSettings.of(context(mapOf("environment" to "demo")))
        assertThat(demo.environment.mode).isEqualTo(TradeMode.DEMO)
        assertThat(demo.shard).isEqualTo(0)
        val real = KalshiSettings.of(context(mapOf("environment" to "PRODUCTION", "shard" to "3")))
        assertThat(real.environment.mode).isEqualTo(TradeMode.REAL)
        assertThat(real.environment.restUrl).isEqualTo("https://external-api.kalshi.com/trade-api/v2")
        assertThat(real.shard).isEqualTo(3)
    }

    @Test
    fun `a bad value is refused by its key`() {
        assertThatThrownBy { KalshiSettings.of(context(mapOf("environment" to "testnet"))) }
            .hasMessageContaining("setting environment must be one of demo, production: testnet")
        assertThatThrownBy { KalshiSettings.of(context(mapOf("environment" to "demo", "shard" to "4"))) }
            .hasMessageContaining("setting shard must be a kalshi exchange index, 0 to 3: 4")
        assertThatThrownBy { KalshiSettings.of(context(mapOf("environment" to "demo", "shard" to "one"))) }
            .hasMessageContaining("setting shard must be a whole number")
    }

    @Test
    fun `the factory is kalshi, needs the key, and refuses a secret that is not a private key`() {
        val factory = KalshiAdapterFactory()
        assertThat(factory.type).isEqualTo("kalshi")
        assertThatThrownBy { factory.create(context(mapOf("environment" to "demo"), null)) }
            .hasMessageContaining("credentials are required")
        assertThatThrownBy { factory.create(context(mapOf("environment" to "demo"), Credentials("key-id", "secret"))) }
            .hasMessageContaining("PEM private key")
        factory.create(context(mapOf("environment" to "demo"))).use { adapter ->
            assertThat(adapter.id).isEqualTo("kalshi")
            assertThat(adapter.identity().login).isEqualTo("key-id")
            assertThat(adapter.identity().mode).isEqualTo(TradeMode.DEMO)
        }
    }
}

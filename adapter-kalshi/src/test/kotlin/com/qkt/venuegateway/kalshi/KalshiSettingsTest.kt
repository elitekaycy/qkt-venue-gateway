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

    private val demo = mapOf("environment" to "demo", "series" to "KXINXU")

    private fun context(
        settings: Map<String, String>,
        credentials: Credentials? = Credentials("key-id", Fixtures.pem),
    ) = AdapterContext(settings, { 0L }, dir, credentials)

    @Test
    fun `the environment has no default, since production is real money, the shard defaults to 0`() {
        assertThatThrownBy { KalshiSettings.of(context(emptyMap())) }
            .hasMessageContaining("setting environment is required")
        val test = KalshiSettings.of(context(demo))
        assertThat(test.environment.mode).isEqualTo(TradeMode.DEMO)
        assertThat(test.shard).isEqualTo(0)
        val real = KalshiSettings.of(context(demo + mapOf("environment" to "PRODUCTION", "shard" to "3")))
        assertThat(real.environment.mode).isEqualTo(TradeMode.REAL)
        assertThat(real.environment.restUrl).isEqualTo("https://external-api.kalshi.com/trade-api/v2")
        assertThat(real.shard).isEqualTo(3)
    }

    @Test
    fun `a bad value is refused by its key`() {
        assertThatThrownBy { KalshiSettings.of(context(mapOf("environment" to "testnet"))) }
            .hasMessageContaining("setting environment must be one of demo, production: testnet")
        assertThatThrownBy { KalshiSettings.of(context(demo + ("shard" to "4"))) }
            .hasMessageContaining("setting shard must be a kalshi exchange index, 0 to 3: 4")
        assertThatThrownBy { KalshiSettings.of(context(demo + ("shard" to "one"))) }
            .hasMessageContaining("setting shard must be a whole number")
    }

    @Test
    fun `the series have no default, since the whole catalogue is many minutes of reading, and a star asks for it`() {
        assertThatThrownBy { KalshiSettings.of(context(mapOf("environment" to "demo"))) }
            .hasMessageContaining("setting series is required")
        val named = KalshiSettings.of(context(demo + ("series" to " KXINXU, KXBTCD ,KXGOLD15M,KXINXU,")))
        assertThat(named.series).containsExactly("KXINXU", "KXBTCD", "KXGOLD15M")
        assertThat(KalshiSettings.of(context(demo + ("series" to "*"))).series).isEmpty()
        listOf("KXINXU,*", "KX INXU", ",", "KXINXU;KXBTCD").forEach { bad ->
            assertThatThrownBy { KalshiSettings.of(context(demo + ("series" to bad))) }
                .hasMessageContaining("setting series must be kalshi series tickers separated by commas, or *")
                .hasMessageContaining(bad)
        }
    }

    @Test
    fun `the factory is kalshi, needs the key, and refuses a secret that is not a private key`() {
        val factory = KalshiAdapterFactory()
        assertThat(factory.type).isEqualTo("kalshi")
        assertThatThrownBy { factory.create(context(demo, null)) }
            .hasMessageContaining("credentials are required")
        assertThatThrownBy { factory.create(context(demo, Credentials("key-id", "secret"))) }
            .hasMessageContaining("PEM private key")
        factory.create(context(demo)).use { adapter ->
            assertThat(adapter.id).isEqualTo("kalshi")
            assertThat(adapter.identity().login).isEqualTo("key-id")
            assertThat(adapter.identity().mode).isEqualTo(TradeMode.DEMO)
        }
    }
}

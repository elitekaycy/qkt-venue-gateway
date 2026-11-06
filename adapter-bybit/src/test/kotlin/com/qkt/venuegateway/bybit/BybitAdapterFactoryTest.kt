package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueIdentity
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitAdapterFactoryTest {
    @TempDir lateinit var dir: Path
    private val factory = BybitAdapterFactory()

    private fun context(
        settings: Map<String, String>,
        credentials: Credentials? = Credentials("the-key", "the-secret"),
    ) = AdapterContext(settings, { 0L }, dir, credentials)

    @Test
    fun `the factory answers to bybit`() {
        assertThat(factory.type).isEqualTo("bybit")
    }

    @Test
    fun `a mainnet linear account is real money in its settle coin, serving contract data`() {
        val adapter =
            factory.create(context(mapOf("environment" to "mainnet", "category" to "linear", "currency" to "USDC")))

        assertThat(adapter.id).isEqualTo("bybit")
        assertThat(adapter.identity()).isEqualTo(VenueIdentity("the-key", TradeMode.REAL, "USDC"))
        assertThat(adapter.capabilities).contains(Capability.FUNDING, Capability.OPEN_INTEREST)
        adapter.close()
    }

    @Test
    fun `a testnet spot account is demo money, serving no contract data`() {
        val adapter = factory.create(context(mapOf("environment" to "testnet", "category" to "spot")))

        assertThat(adapter.identity()).isEqualTo(VenueIdentity("the-key", TradeMode.DEMO, "USDT"))
        assertThat(adapter.capabilities)
            .contains(Capability.BARS, Capability.QUOTES)
            .doesNotContain(Capability.FUNDING, Capability.LIQUIDATIONS)
        adapter.close()
    }

    @Test
    fun `an account without its api key is refused`() {
        assertThatThrownBy { factory.create(context(BybitVenue.LINEAR, credentials = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("credentials")
    }
}

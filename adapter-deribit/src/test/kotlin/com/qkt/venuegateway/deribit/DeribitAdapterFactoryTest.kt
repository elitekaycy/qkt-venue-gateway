package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import java.nio.file.Path
import java.util.ServiceLoader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `GATEWAY_ADAPTER=deribit` builds the adapter from its settings and the API key, opening nothing until connected. */
class DeribitAdapterFactoryTest {
    private fun context(
        dir: Path,
        environment: String,
        credentials: Credentials? = Credentials("client-7", "s"),
    ) = AdapterContext(mapOf("environment" to environment), { 0L }, dir, credentials)

    @Test
    fun `the gateway finds the factory by its type`() {
        val factory = ServiceLoader.load(VenueAdapterFactory::class.java).single { it.type == "deribit" }

        assertThat(factory).isInstanceOf(DeribitAdapterFactory::class.java)
    }

    @Test
    fun `a mainnet account is real money, named by the api key's client id, in USDC`(
        @TempDir dir: Path,
    ) {
        val adapter = DeribitAdapterFactory().create(context(dir, "mainnet"))

        assertThat(adapter.id).isEqualTo("deribit")
        assertThat(adapter.identity().login).isEqualTo("client-7")
        assertThat(adapter.identity().mode).isEqualTo(TradeMode.REAL)
        assertThat(adapter.identity().currency).isEqualTo("USDC")
        assertThat(adapter.capabilities).contains(Capability.SETTLEMENTS, Capability.DEPTH)
        adapter.close()
    }

    @Test
    fun `a testnet account is demo money`(
        @TempDir dir: Path,
    ) {
        val adapter = DeribitAdapterFactory().create(context(dir, "testnet"))

        assertThat(adapter.identity().mode).isEqualTo(TradeMode.DEMO)
        adapter.close()
    }

    @Test
    fun `an account without an api key is refused before anything is built`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { DeribitAdapterFactory().create(context(dir, "testnet", credentials = null)) }
            .hasMessageContaining("credentials are required")
    }
}

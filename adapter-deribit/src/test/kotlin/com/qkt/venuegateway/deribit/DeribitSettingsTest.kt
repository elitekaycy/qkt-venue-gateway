package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.client.DeribitException
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeribitSettingsTest {
    @Test
    fun `testnet is a demo account on the test hosts, mainnet a real one, and the environment is never defaulted`() {
        val testnet = DeribitSettings.of(mapOf("environment" to "testnet"))
        val mainnet = DeribitSettings.of(mapOf("environment" to "mainnet", "stop_trigger" to "mark_price"))

        assertThat(testnet.environment.socketUrl).isEqualTo("wss://test.deribit.com/ws/api/v2")
        assertThat(testnet.environment.httpUrl).isEqualTo("https://test.deribit.com")
        assertThat(testnet.environment.mode).isEqualTo(TradeMode.DEMO)
        assertThat(testnet.currency).isEqualTo("USDC")
        assertThat(testnet.stopTrigger).isEqualTo("last_price")
        assertThat(mainnet.environment.socketUrl).isEqualTo("wss://www.deribit.com/ws/api/v2")
        assertThat(mainnet.environment.mode).isEqualTo(TradeMode.REAL)
        assertThat(mainnet.stopTrigger).isEqualTo("mark_price")
        assertThatThrownBy { DeribitSettings.of(emptyMap()) }.hasMessageContaining("adapter.settings.environment")
    }

    @Test
    fun `coin-margined contracts, an unknown environment and an unknown stop trigger are refused by name`() {
        assertThatThrownBy { DeribitSettings.of(mapOf("environment" to "testnet", "currency" to "BTC")) }
            .hasMessageContaining("BTC")
            .hasMessageContaining("USDC")
        assertThatThrownBy { DeribitSettings.of(mapOf("environment" to "prod")) }.hasMessageContaining("prod")
        assertThatThrownBy { DeribitSettings.of(mapOf("environment" to "testnet", "stop_trigger" to "bid")) }
            .hasMessageContaining("bid")
    }

    @Test
    fun `deribit's documented outage and throttling codes are unavailable, every other answer is a refusal`() {
        listOf(
            10028,
            10040,
            10041,
            10047,
            10066,
            11051,
            11094,
            13028,
            13503,
            13888,
            10001,
            13025,
            10000,
            13009,
        ).forEach {
            assertThat(
                DeribitErrors.translate(DeribitException(it, "x")),
            ).describedAs("code $it").isInstanceOf(VenueUnavailableException::class.java)
        }
        val refused =
            DeribitErrors.translate(
                DeribitException(-32602, "Invalid params", "must be a multiple of contract size"),
            )
        assertThat(refused).isInstanceOf(VenueRefusedException::class.java)
        assertThat((refused as VenueRefusedException).reason).contains("must be a multiple of contract size")
        listOf(10009, 10034, 10035, 11044, 13004).forEach {
            assertThat(
                DeribitErrors.translate(DeribitException(it, "x")),
            ).describedAs("code $it").isInstanceOf(VenueRefusedException::class.java)
        }
        assertThat(DeribitErrors.translate(IOException("gone"))).isInstanceOf(VenueUnavailableException::class.java)
    }
}

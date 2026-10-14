package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.math.BigDecimal
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Orders go to Deribit only for contracts the account's listing holds (the recorded USDC futures). */
class DeribitPlaceGuardTest {
    private val listed =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/instruments-future.json")!!.readText())
            .jsonObject["result"]!!
            .jsonArray
            .map { DeribitJson.instrument(it.jsonObject) }

    private val market =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(DeribitMarketData::class.java),
        ) { _, method, args ->
            when (method.name) {
                "instruments" -> if (args[1] == "future") listed else emptyList<DeribitInstrument>()
                else -> error("market ${method.name} is not used here")
            }
        } as DeribitMarketData

    private val open =
        DeribitPrivateJson.order(
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/private/buy-limit-open.json")!!.readText())
                .jsonObject["response"]!!
                .jsonObject["result"]!!
                .jsonObject["order"]!!
                .jsonObject,
        )

    private fun order(symbol: String) =
        NewOrder(
            "kit-1",
            symbol,
            Side.BUY,
            OrderType.LIMIT,
            BigDecimal("0.0001"),
            BigDecimal("50000"),
            null,
            TimeInForce.GTC,
        )

    @Test
    fun `a coin-margined or unlisted code is refused before it reaches deribit, a listed one is placed`(
        @TempDir dir: Path,
    ) {
        val deribit = ScriptedDeribit(open)
        val (adapter, _) = deribit.adapter(dir, market)

        assertThatThrownBy { adapter.place(order("BTC-PERPETUAL")) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("not a listed USDC contract")
        assertThat(adapter.place(order("BTC_USDC-PERPETUAL")).clientOrderId).isEqualTo("kit-probe-1790866831")
        adapter.close()
    }
}

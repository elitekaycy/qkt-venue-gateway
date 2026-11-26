package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.objects
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The trade stream around a target re-sent with its stop as one OCO (recorded on paper, MSFT). */
class AlpacaExitStreamTest {
    @Test
    fun `the re-sent target's cancellation is never pushed, and the stop leg's updates come under the stop's label`() {
        val target = Fixtures.array("orders-open-exit-target.json").single().need("id")
        val w =
            WiredAdapter(
                DeskFixture.assets +
                    mapOf(
                        "POST /v2/orders" to listOf("403:error-exit-stop-held-qty.json", "order-exit-oco.json"),
                        "GET /v2/orders" to listOf("orders-open-exit-target.json"),
                        "DELETE /v2/orders/$target" to listOf(""),
                        "GET /v2/orders/$target" to listOf("order-exit-target-cancelled.json"),
                    ),
                setOf(AlpacaSettings.EQUITY, AlpacaSettings.CRYPTO),
            )
        val heard = HeardListener().also(w.adapter::connect)
        val stop = "dsl-pair-sl.mi1783a"
        val two = BigDecimal("2")
        w.adapter.place(NewOrder(stop, "MSFT", Side.SELL, OrderType.STOP, two, null, two * two, TimeInForce.GTC))
        val cancel = Fixtures.updates("ws-trade-updates.json").single { it["event"] == JsonPrimitive("canceled") }
        val cancelled = (cancel["order"] as JsonObject).with("id" to target)
        w.update(JsonObject(cancel + ("order" to cancelled)))
        assertThat(heard.orders).isEmpty()
        val leg = Fixtures.obj("order-exit-oco.json").objects("legs").single()
        w.update(JsonObject(cancel + ("event" to JsonPrimitive("new")) + ("order" to leg)))
        assertThat(heard.orders.map { it.clientOrderId }).containsExactly(stop)
    }
}

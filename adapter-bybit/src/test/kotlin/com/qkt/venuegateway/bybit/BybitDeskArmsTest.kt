package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitDeskArmsTest {
    private val working = firstRecord("private/order-limit-new.json")
    private val cancelled = firstRecord("private/order-limit-cancelled.json")
    private val server =
        Canned { path, query ->
            when {
                "cursor" in query -> Fixtures.text("private/executions-empty.json")
                path == "/v5/order/realtime" ->
                    answerWith(
                        "private/open-orders.json",
                        listOf(
                            working,
                            cancelled.with("orderLinkId" to "rec-cancelled"),
                            working.with(
                                "orderLinkId" to null,
                            ),
                        ),
                    )
                else -> null
            }
        }
    private val settings = bybitSettings(mapOf("environment" to "testnet", "category" to "linear"))
    private val desk =
        BybitOrderDesk(
            BybitPrivateClient(BybitRest(server.url, "k", "s"), "linear", "USDT"),
            settings,
            BybitListing({ BybitPublicClient(BybitRest(server.url)).instruments("linear") }, "USDT", { 0L }, 600_000),
            BybitPositionModes({ emptyList() }, { null }, true, { 0L }),
        )

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `only the gateway's working orders are open, not its ended ones nor another client's`() {
        val open = desk.open()

        assertThat(open.map { it.clientOrderId }).containsExactly(working["orderLinkId"].toString().trim('"'))
        assertThat(open.single().status).isEqualTo(OrderStatus.WORKING)
    }
}

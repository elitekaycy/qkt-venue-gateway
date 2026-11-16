package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AlpacaDeskClockTest {
    private var now = 0L

    private fun desk(routes: Map<String, List<String>>): AlpacaOrderDesk {
        val http =
            FixtureHttp(
                mapOf(
                    "GET /v2/assets" to listOf("assets-crypto.json"),
                    "GET /v2/assets/AAPL" to listOf("asset-AAPL.json"),
                ) + routes,
            )
        val trading = AlpacaTrading(http)
        val listing =
            AlpacaListing(trading, linkedSetOf(AlpacaSettings.EQUITY, AlpacaSettings.CRYPTO), { now })
        return AlpacaOrderDesk(
            trading,
            listing,
            AlpacaSettings(AlpacaEnvironment.PAPER),
            { BigDecimal.ZERO },
            { now },
            {},
            settleMs = 1_000,
        )
    }

    @Test
    fun `an order just cancelled is hidden from the open list until Alpaca has had time to drop it`() {
        val working = Fixtures.obj("order-by-label-working.json")
        val desk =
            desk(
                mapOf(
                    "GET /v2/orders:by_client_order_id" to
                        listOf(
                            "order-by-label-working.json",
                            "order-by-label-working.json",
                            "order-by-label-cancelled.json",
                        ),
                    "DELETE /v2/orders/${working.text("id")}" to listOf(""),
                    "GET /v2/orders" to listOf("orders-open.json"),
                ),
            )
        desk.cancel(working.text("client_order_id")!!)

        val hidden = desk.open()
        now = 5_000
        val shown = desk.open()

        assertThat(hidden).isEmpty()
        assertThat(shown.map { it.clientOrderId }).contains(working.text("client_order_id"))
    }
}

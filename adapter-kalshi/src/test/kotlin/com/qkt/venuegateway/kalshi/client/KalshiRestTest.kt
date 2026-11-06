package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiRestTest {
    private val server =
        Fixtures.Server { method, path, _, _ ->
            when ("$method $path") {
                "GET /exchange/status" -> "!503"
                "GET /portfolio/orders/01a1ae61-bf60-7752-859e-2842fa23b0ba" -> "private/order-amended.json"
                "POST /portfolio/events/orders/01a1ae61-bf60-7752-859e-2842fa23b0ba/amend" -> "private/amend.json"
                else -> null
            }
        }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `an answer without kalshi's error body is named by its status, and the client never prints a key`() {
        val rest = KalshiRest(server.restUrl)

        assertThatThrownBy { rest.get("/exchange/status") }
            .isInstanceOf(KalshiException::class.java)
            .hasMessageContaining("503 http_503: GET /exchange/status")
        assertThatThrownBy { rest.orNull { rest.get("/exchange/status") } }.isInstanceOf(KalshiException::class.java)
        assertThat(rest.toString()).isEqualTo("KalshiRest(${server.restUrl})")
        val unsigned = server.requests.first()
        assertThat(unsigned.headers["KALSHI-ACCESS-KEY"]).describedAs("unsigned").isNull()
    }

    @Test
    fun `an order without a label is amended without one`() {
        val portfolio = KalshiPortfolio(KalshiRest(server.restUrl, KalshiAuth("key-id", Fixtures.pem)))
        val order = portfolio.order("01a1ae61-bf60-7752-859e-2842fa23b0ba")!!.copy(clientOrderId = null)

        portfolio.amend(order, BigDecimal("2"), BigDecimal("0.02"))

        val sent = server.requests.last()
        val body = Json.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertThat(body.keys).containsExactlyInAnyOrder("ticker", "side", "price", "count")
    }
}

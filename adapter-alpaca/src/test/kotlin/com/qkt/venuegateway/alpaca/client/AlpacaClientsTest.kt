package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.alpaca.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The clients over real HTTP (MockWebServer serving recorded answers): headers, paths, paging, and errors. */
class AlpacaClientsTest {
    private val server = MockWebServer().apply { start() }
    private val http =
        AlpacaJsonHttp(server.url("").toString().trimEnd('/'), "key-id", "secret-value", okhttp3.OkHttpClient())

    @AfterEach
    fun stop() = server.close()

    private fun answer(
        fixture: String,
        status: Int = 200,
    ) = server.enqueue(MockResponse(code = status, body = Fixtures.text(fixture)))

    @Test
    fun `every request carries the key headers, and a non-2xx answer keeps Alpaca's body`() {
        answer("account.json")
        AlpacaTrading(http).account()
        val request = server.takeRequest()
        assertThat(request.headers["APCA-API-KEY-ID"]).isEqualTo("key-id")
        assertThat(request.headers["APCA-API-SECRET-KEY"]).isEqualTo("secret-value")
        assertThat(request.target).isEqualTo("/v2/account")
        answer("error-buying-power.json", 403)
        assertThatThrownBy { AlpacaTrading(http).account() }
            .isInstanceOfSatisfying(AlpacaHttpException::class.java) {
                assertThat(it.status).isEqualTo(403)
                assertThat(it.body).contains("insufficient buying power")
            }
    }

    @Test
    fun `a label Alpaca never saw is null, and a cancel's empty 204 is an answer`() {
        answer("order-by-label-unknown.json", 404)
        assertThat(AlpacaTrading(http).orderByClientId("qkt-never-placed")).isNull()
        assertThat(server.takeRequest().url.queryParameter("client_order_id")).isEqualTo("qkt-never-placed")
        server.enqueue(MockResponse(code = 204))
        assertThat(AlpacaTrading(http).cancel("id-1")).isTrue
        answer("order-cancel-unknown.json", 404)
        assertThat(AlpacaTrading(http).cancel("id-2")).isFalse
    }

    @Test
    fun `bars page on next_page_token until Alpaca sends none, from the account's feed`() {
        answer("bars-stocks-1min-page.json")
        answer("bars-stocks-1hour-last.json")
        val last =
            (
                Fixtures.obj(
                    "bars-stocks-1hour-last.json",
                )["bars"] as kotlinx.serialization.json.JsonObject
            )["AAPL"]
        val bars = AlpacaData(http, "iex").bars("AAPL", false, "1Min", 0L, 1_800_000_000_000L)
        assertThat(bars).hasSize(3 + (last as kotlinx.serialization.json.JsonArray).size)
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest().url
        assertThat(first.encodedPath).isEqualTo("/v2/stocks/bars")
        assertThat(first.queryParameter("feed")).isEqualTo("iex")
        assertThat(first.queryParameter("page_token")).isNull()
        assertThat(
            server.takeRequest().url.queryParameter("page_token"),
        ).isEqualTo("QUFQTHxNfDE3OTMwMTc5MjAwMDAwMDAwMDA=")
    }

    @Test
    fun `coin bars come from the crypto API, which takes no feed`() {
        answer("bars-crypto-1min-last.json")
        assertThat(AlpacaData(http, "iex").bars("BTC/USD", true, "1Min", 0L, 1_800_000_000_000L)).isNotEmpty
        val url = server.takeRequest().url
        assertThat(url.encodedPath).isEqualTo("/v1beta3/crypto/us/bars")
        assertThat(url.queryParameter("feed")).isNull()
    }

    @Test
    fun `fills page by the last activity's id while pages are full`() {
        answer("activities-fill.json")
        val fills = AlpacaTrading(http).fills(0L, 1_800_000_000_000L)
        assertThat(fills).hasSize(3)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an order is posted and a replace is patched, each with its JSON body`() {
        answer("order-limit-accepted.json")
        answer("order-replace.json")
        val placed = Fixtures.obj("order-limit-accepted.json")
        val trading = AlpacaTrading(http)
        assertThat(trading.submit(placed)).isEqualTo(placed)
        trading.replace("id-1", Fixtures.obj("order-replace.json"))
        val post = server.takeRequest()
        assertThat(post.method).isEqualTo("POST")
        assertThat(post.target).isEqualTo("/v2/orders")
        assertThat(post.body!!.utf8()).isEqualTo(placed.toString())
        val patch = server.takeRequest()
        assertThat(patch.method).isEqualTo("PATCH")
        assertThat(patch.target).isEqualTo("/v2/orders/id-1")
    }

    @Test
    fun `fills read the next page after the last activity's id while a page is full`() {
        val activity = Fixtures.array("activities-fill.json").first()
        val full = JsonArray(List(100) { activity }).toString()
        server.enqueue(MockResponse(code = 200, body = full))
        answer("activities-fill.json")
        assertThat(AlpacaTrading(http).fills(0L, 1_800_000_000_000L)).hasSize(103)
        assertThat(server.takeRequest().url.queryParameter("page_token")).isNull()
        val token = (activity["id"] as JsonPrimitive).content
        assertThat(server.takeRequest().url.queryParameter("page_token")).isEqualTo(token)
    }
}

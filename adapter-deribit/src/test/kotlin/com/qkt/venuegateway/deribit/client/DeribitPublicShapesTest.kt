package com.qkt.venuegateway.deribit.client

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DeribitPublicShapesTest {
    private val server = MockWebServer().apply { start() }
    private val client = DeribitPublicClient(server.url("/").toString().trimEnd('/'))

    @AfterEach
    fun stop() = server.close()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private fun answer(body: String) = server.enqueue(MockResponse(body = body))

    private fun envelope(name: String): JsonObject = Json.parseToJsonElement(fixture(name)).jsonObject

    @Test
    fun `an error carries its code and message, with zero and unknown for what it leaves out`() {
        answer(fixture("trades-count-too-high.json"))
        answer("""{"jsonrpc":"2.0","error":{}}""")

        assertThatThrownBy { client.tape("BTC_USDC-PERPETUAL", 0, 10, 5000) }
            .isInstanceOfSatisfying(DeribitException::class.java) {
                assertThat(it.code).isEqualTo(-32602)
                assertThat(it.message).contains("Invalid params")
            }
        assertThatThrownBy { client.tape("BTC_USDC-PERPETUAL", 0, 10, 5) }
            .isInstanceOfSatisfying(DeribitException::class.java) {
                assertThat(it.code).isZero
                assertThat(it.message).contains("unknown")
            }
    }

    @Test
    fun `an answer with no result, or that is no envelope, is the venue being unreachable`() {
        answer("""{"jsonrpc":"2.0"}""")
        answer("[1,2]")

        assertThatThrownBy { client.ticker("BTC_USDC-PERPETUAL") }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("no result")
        assertThatThrownBy { client.ticker("BTC_USDC-PERPETUAL") }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("not a JSON-RPC envelope")
    }

    @Test
    fun `a page says it has more only when the venue says so`() {
        val base = envelope("trades-from-time.json")
        val result = base["result"]!!.jsonObject

        fun withMore(more: JsonPrimitive?) =
            JsonObject(
                base +
                    ("result" to JsonObject(if (more == null) result - "has_more" else result + ("has_more" to more))),
            )
        answer(withMore(JsonPrimitive(true)).toString())
        answer(withMore(JsonPrimitive(false)).toString())
        answer(withMore(null).toString())

        assertThat(client.tape("BTC_USDC-PERPETUAL", 0, 10, 5).more).isTrue
        assertThat(client.tape("BTC_USDC-PERPETUAL", 0, 10, 5).more).isFalse
        assertThat(client.tape("BTC_USDC-PERPETUAL", 0, 10, 5).more).isFalse
    }

    @Test
    fun `funding rates outside the range asked are dropped from what the venue sent`() {
        answer(fixture("funding-rate-history.json"))

        val rates = client.fundingRates("SOL_USDC-PERPETUAL", 1_791_122_400_000L, 1_791_126_000_000L)

        assertThat(rates.map { it.timestampMs }).containsExactly(1_791_122_400_000L, 1_791_126_000_000L)
    }
}

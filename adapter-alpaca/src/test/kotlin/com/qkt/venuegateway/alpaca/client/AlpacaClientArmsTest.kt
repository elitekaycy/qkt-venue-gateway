package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.alpaca.FixtureHttp
import com.qkt.venuegateway.alpaca.Fixtures
import com.qkt.venuegateway.support.SubscriptionSet
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AlpacaClientArmsTest {
    @Test
    fun `an answer that is not json, not an object or not a list is refused naming what was read`() {
        assertThatThrownBy { AlpacaJson.parse("{not json", "assets") }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("alpaca assets answered without JSON")
        assertThatThrownBy { AlpacaJson.obj("[1]", "account") }.hasMessageContaining("account answered no object")
        assertThatThrownBy { AlpacaJson.array("{}", "orders") }.hasMessageContaining("orders answered no list")
    }

    @Test
    fun `a refusal says what Alpaca wrote, or its status when it wrote none in json`() {
        val written = AlpacaHttpException(422, Fixtures.text("error-sub-penny.json"))

        assertThat(written.message).isNotEqualTo("HTTP 422").doesNotContain("HTTP")
        assertThat(AlpacaHttpException(500, """{"code":1}""").message).isEqualTo("HTTP 500")
        assertThat(AlpacaHttpException(502, "<html>bad gateway</html>").message).isEqualTo("HTTP 502")
        assertThat(AlpacaHttpException(503, "[1]").message).isEqualTo("HTTP 503")
    }

    @Test
    fun `bars of a symbol Alpaca holds none of, or answered without any, are none, and a stock asks for its feed`() {
        val none = FixtureHttp(mapOf("GET /v2/stocks/bars" to listOf("bars-stocks-1hour-last.json")))
        val bars = AlpacaData(none, "sip").bars("ZZZ", false, "1Min", 0, 60_000)
        val crypto = FixtureHttp(mapOf("GET /v1beta3/crypto/us/bars" to listOf("bars-crypto-1min-last.json")))
        AlpacaData(crypto, "sip").bars("BTC/USD", true, "1Min", 0, 60_000)

        assertThat(bars).isEmpty()
        assertThat(
            none.sent
                .first()
                .params
                .toMap()["feed"],
        ).isEqualTo("sip")
        assertThat(
            crypto.sent
                .first()
                .params
                .toMap(),
        ).doesNotContainKey("feed")
    }

    private fun greet(
        stream: AlpacaQuoteStream,
        name: String,
        upTo: Int,
    ) = Fixtures.frames(name).take(upTo).forEach(stream::text)

    @Test
    fun `the data stream signs in and subscribes what is wanted once greeted and authenticated`() {
        val wanted = SubscriptionSet<String>().also { it.want(listOf("AAPL")) }
        val quotes = CopyOnWriteArrayList<JsonObject>()
        val feed = CopyOnWriteArrayList<Pair<Boolean, String>>()
        val stream =
            AlpacaQuoteStream("k", "s", wanted, { quotes += it }, { up, why -> feed += up to why }, replyMs = 200)
        val sent = CopyOnWriteArrayList<String>()
        greet(stream, "ws-data-iex.json", 2)

        stream.opening {
            sent += it
            true
        }
        stream.up()
        stream.text("""{"T":"success"}""")
        stream.text("""[{"T":"q","S":"AAPL"}]""")
        stream.text("""[{"T":"other"}]""")
        stream.text("""[{"x":1}]""")
        stream.text("""{"not":"a list"}""")
        stream.text("""["x",{"T":"q","S":"MSFT"}]""")

        assertThat(sent[0]).contains("\"action\":\"auth\"")
        assertThat(sent[1]).contains("subscribe", "AAPL")
        assertThat(quotes.map { it["S"].toString() }).containsExactly("\"AAPL\"", "\"MSFT\"")
        assertThat(feed).containsExactly(true to "alpaca data stream subscribed")
    }

    @Test
    fun `a data stream with nothing wanted subscribes nothing, and a refused sign-in names the venue's code`() {
        val stream = AlpacaQuoteStream("k", "s", SubscriptionSet(), {}, { _, _ -> }, replyMs = 200)
        val sent = CopyOnWriteArrayList<String>()
        greet(stream, "ws-data-iex.json", 2)
        stream.opening {
            sent += it
            true
        }

        val refused = AlpacaQuoteStream("k", "s", SubscriptionSet(), {}, { _, _ -> }, replyMs = 200)
        greet(refused, "ws-data-unauthorized.json", 2)

        assertThat(sent).hasSize(1)
        assertThatThrownBy { refused.opening { true } }.hasRootCauseMessage("alpaca data stream error 402 auth failed")
        assertThat(stream.subscription(emptyList())).isNull()
        assertThat(stream.unsubscription(emptyList())).isNull()
        assertThat(stream.unsubscription(listOf("AAPL"))).contains("unsubscribe", "AAPL")
    }

    @Test
    fun `a data stream dropped while up says so, once, and one dropped before it was up says nothing`() {
        val feed = CopyOnWriteArrayList<Pair<Boolean, String>>()
        val stream = AlpacaQuoteStream("k", "s", SubscriptionSet(), {}, { up, why -> feed += up to why })

        stream.dropped("closed", wasUp = false)
        stream.dropped("closed 1006", wasUp = true)

        assertThat(feed).containsExactly(false to "alpaca data stream: closed 1006")
    }

    @Test
    fun `the trade stream refuses a login that is not authorized, and reads a status-less answer as such`() {
        val refused = AlpacaTradeStream("k", "s", {}, { _, _ -> }, replyMs = 200)
        Fixtures.frames("ws-trade-unauthorized.json").forEach(refused::text)
        val noStatus = AlpacaTradeStream("k", "s", {}, { _, _ -> }, replyMs = 200)
        noStatus.text("""{"stream":"authorization","data":{"action":"auth"}}""")

        assertThatThrownBy { refused.opening { true } }.hasMessageContaining("login unauthorized")
        assertThatThrownBy { noStatus.opening { true } }.hasMessageContaining("login without a status")
    }

    @Test
    fun `the trade stream ignores a frame with no data or of another stream, and reports a drop only when it was up`() {
        val updates = CopyOnWriteArrayList<JsonObject>()
        val links = CopyOnWriteArrayList<Pair<Boolean, String>>()
        val stream = AlpacaTradeStream("k", "s", { updates += it }, { up, why -> links += up to why })

        stream.text("""{"stream":"trade_updates"}""")
        stream.text("""{"data":{"x":1}}""")
        stream.text("""{"stream":"other","data":{"x":1}}""")
        stream.text("""{"stream":"trade_updates","data":{"event":"fill"}}""")
        stream.up()
        stream.dropped("closed", wasUp = false)
        stream.dropped("closed 1006", wasUp = true)

        assertThat(updates).hasSize(1)
        assertThat(links).containsExactly(
            true to "alpaca trade stream listening",
            false to "alpaca trade stream: closed 1006",
        )
    }
}

package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.alpaca.Fixtures
import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.SocketLink
import com.qkt.venuegateway.support.SubscriptionSet
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The stream protocols replaying recorded sessions: each frame Alpaca sent answers the frame the protocol sent. */
class AlpacaStreamsTest {
    /** A link that answers the n-th frame sent with Alpaca's recorded [replies] n, read back into [read]. */
    private class Replaying(
        private val replies: List<String>,
        private val read: (String) -> Unit,
    ) : SocketLink {
        val sent = CopyOnWriteArrayList<String>()

        override fun send(frame: String): Boolean {
            sent += frame
            replies.getOrNull(sent.size - 1)?.let(read)
            return true
        }
    }

    @Test
    fun `the trade stream logs in, listens once authorized, comes up listening, and pushes every update`() {
        val frames = Fixtures.frames("ws-trade-updates-fill.json")
        val updates = mutableListOf<JsonObject>()
        val links = mutableListOf<Pair<Boolean, String>>()
        val stream =
            AlpacaTradeStream("key", "secret", { updates += it }, { up, why -> links += up to why }, replyMs = 1_000)
        val link = Replaying(frames.take(2), stream::text)
        stream.opening(link)
        assertThat(link.sent[0]).contains("\"action\":\"auth\"", "\"key\":\"key\"")
        assertThat(link.sent[1]).contains("trade_updates")
        stream.up()
        frames.drop(2).forEach(stream::text)
        assertThat(updates.map { it.text("event") }).containsExactly(
            "pending_new",
            "new",
            "partial_fill",
            "fill",
            "pending_new",
            "new",
            "pending_replace",
            "accepted",
            "replaced",
            "canceled",
            "pending_new",
            "new",
            "canceled",
        )
        stream.dropped("closed", wasUp = true)
        assertThat(links.map { it.first }).containsExactly(true, false)
    }

    @Test
    fun `a refused trade-stream login fails the opening, so the link never comes up`() {
        val stream = AlpacaTradeStream("key", "secret", {}, { _, _ -> }, replyMs = 1_000)
        val link = Replaying(Fixtures.frames("ws-trade-unauthorized.json"), stream::text)
        assertThatThrownBy { stream.opening(link) }.hasMessageContaining("unauthorized")
        assertThat(link.sent).hasSize(1)
    }

    @Test
    fun `a data stream logs in when greeted, subscribes all it carries once authenticated, and pushes quotes`() {
        val frames = Fixtures.frames("ws-data-crypto.json")
        val quotes = mutableListOf<JsonObject>()
        val symbols = SubscriptionSet<String>().apply { want(listOf("BTC/USD")) }
        val stream = AlpacaQuoteStream("key", "secret", symbols, { quotes += it }, { _, _ -> }, replyMs = 1_000)
        stream.text(frames[0])
        val link = Replaying(frames.drop(1), stream::text)
        stream.opening(link)
        assertThat(link.sent[0]).contains("\"action\":\"auth\"")
        assertThat(link.sent[1]).contains("\"quotes\":[\"BTC/USD\"]")
        frames.drop(3).forEach(stream::text)
        assertThat(quotes).isNotEmpty.allSatisfy { assertThat(it.text("S")).isEqualTo("BTC/USD") }
        assertThat(stream.subscription(emptyList())).isNull()
    }

    @Test
    fun `a refused data-stream login fails the opening`() {
        val frames = Fixtures.frames("ws-data-unauthorized.json")
        val stream = AlpacaQuoteStream("key", "secret", SubscriptionSet(), {}, { _, _ -> }, replyMs = 1_000)
        stream.text(frames[0])
        assertThatThrownBy { stream.opening(Replaying(frames.drop(1), stream::text)) }.hasMessageContaining("402")
    }

    @Test
    fun `the stock feed after hours confirms its subscription and pushes nothing`() {
        val frames = Fixtures.frames("ws-data-iex.json")
        val quotes = mutableListOf<JsonObject>()
        val stream =
            AlpacaQuoteStream(
                "key",
                "secret",
                SubscriptionSet<String>().apply {
                    want(listOf("AAPL"))
                },
                { quotes += it },
                { _, _ -> },
            )
        stream.text(frames[0])
        stream.opening(Replaying(frames.drop(1), stream::text))
        assertThat(quotes).isEmpty()
    }

    @Test
    fun `a second data connection on one key is refused by Alpaca, and the opening fails naming it`() {
        val frames = Fixtures.frames("ws-data-connection-limit.json")
        val stream = AlpacaQuoteStream("key", "secret", SubscriptionSet(), {}, { _, _ -> }, replyMs = 1_000)
        stream.text(frames[0])
        assertThatThrownBy { stream.opening(Replaying(frames.drop(1), stream::text)) }
            .hasMessageContaining("406")
            .hasMessageContaining("connection limit exceeded")
    }
}

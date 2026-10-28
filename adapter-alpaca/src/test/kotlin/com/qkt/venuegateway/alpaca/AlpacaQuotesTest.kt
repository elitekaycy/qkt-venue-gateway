package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaQuoteStream
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.SubscriptionSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AlpacaQuotesTest {
    private val routes =
        mapOf(
            "GET /v2/assets" to listOf("assets-crypto.json"),
            "GET /v2/assets/AAPL" to listOf("asset-AAPL.json"),
            "GET /v2/assets/NOPE" to listOf("404:asset-unknown.json"),
        )
    private val listing = AlpacaListing(AlpacaTrading(FixtureHttp(routes)), setOf("us_equity", "crypto"), { 0L })

    private class Opened(
        val feed: String,
        val symbols: SubscriptionSet<String>,
    ) : AlpacaQuoteSocket {
        val sent = mutableListOf<String>()
        var started = false

        override fun start() {
            started = true
        }

        override fun send(frame: String) = sent.add(frame)

        override fun close() {}
    }

    private fun quotes(overnight: Boolean): Pair<AlpacaQuotes, MutableList<Opened>> {
        val opened = mutableListOf<Opened>()
        val quotes =
            AlpacaQuotes(listing, overnight) { feed, symbols ->
                val socket = Opened(feed, symbols).also(opened::add)
                AlpacaQuoteStream("k", "s", symbols, {}, { _, _ -> }) to socket
            }
        return quotes to opened
    }

    @Test
    fun `shares quote on the stock feed, coin pairs on the crypto feed, each opened at its first subscription`() {
        val (quotes, opened) = quotes(overnight = false)
        quotes.subscribe(setOf("AAPL", "BTCUSD", "NOPE"))
        assertThat(opened.map { it.feed to it.symbols.all() })
            .containsExactlyInAnyOrder("stocks" to listOf("AAPL"), "crypto" to listOf("BTC/USD"))
        assertThat(opened).allSatisfy { assertThat(it.started).isTrue }
    }

    @Test
    fun `each subscription replaces the last, subscribing what is new and unsubscribing what is no longer asked for`() {
        val (quotes, opened) = quotes(overnight = false)
        quotes.subscribe(setOf("AAPL", "BTCUSD"))
        quotes.subscribe(setOf("AAPL", "BTCUSD"))
        assertThat(opened.flatMap { it.sent }).isEmpty()
        quotes.subscribe(setOf("ETHUSD"))
        val crypto = opened.single { it.feed == "crypto" }
        assertThat(crypto.sent).hasSize(2)
        assertThat(crypto.sent[0]).contains("\"unsubscribe\"", "BTC/USD")
        assertThat(crypto.sent[1]).contains("\"subscribe\"", "ETH/USD")
        assertThat(crypto.symbols.all()).containsExactly("ETH/USD")
        val stocks = opened.single { it.feed == "stocks" }
        assertThat(stocks.sent.single()).contains("\"unsubscribe\"", "AAPL")
        assertThat(stocks.symbols.all()).isEmpty()
    }

    @Test
    fun `an account trading outside regular hours also quotes its shares on the overnight feed`() {
        val (quotes, opened) = quotes(overnight = true)
        quotes.subscribe(setOf("AAPL"))
        assertThat(opened.map { it.feed }).containsExactlyInAnyOrder("stocks", "overnight")
    }
}

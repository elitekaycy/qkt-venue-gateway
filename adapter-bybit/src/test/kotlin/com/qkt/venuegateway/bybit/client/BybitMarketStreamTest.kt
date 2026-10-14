package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.Fixtures
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitMarketStreamTest {
    private val server = MockWebServer().apply { start() }
    private val sent = LinkedBlockingQueue<String>()
    private var venue: WebSocket? = null
    private val tickers = CopyOnWriteArrayList<BybitTicker>()
    private val trades = CopyOnWriteArrayList<Pair<String, List<BybitPrint>>>()
    private val liquidations = CopyOnWriteArrayList<Pair<String, List<BybitPrint>>>()
    private val links = CopyOnWriteArrayList<Boolean>()

    private fun stream(contracts: Boolean): BybitMarketStream {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        venue = webSocket
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        sent += text
                    }
                },
            ),
        )
        val url = server.url("/v5/public/linear").toString().replace("http", "ws")
        return BybitMarketStream(url, contracts, { tickers += it }, { s, p -> trades += s to p }, { s, p ->
            liquidations +=
                s to p
        }) { up, _ ->
            links += up
        }
    }

    private fun until(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `a quoted contract merges its ticker snapshot, deltas and best levels into one ticker`() {
        val stream = stream(contracts = true)
        stream.quote(listOf("BTCUSDT"))
        stream.start()
        until { links.contains(true) }
        assertThat(
            sent.poll(5, TimeUnit.SECONDS),
        ).isEqualTo("""{"op":"subscribe","args":["tickers.BTCUSDT","orderbook.1.BTCUSDT"]}""")

        venue!!.send(Fixtures.text("ws-linear-ticker-snapshot.json"))
        venue!!.send(Fixtures.text("ws-linear-ticker-delta.json"))
        venue!!.send(Fixtures.text("ws-linear-orderbook-1.json"))
        until { tickers.size == 3 }

        val afterDelta = tickers[1]
        assertThat(afterDelta.bid).isEqualByComparingTo("86346.80")
        assertThat(afterDelta.mark).isEqualTo(tickers[0].mark).isNotNull
        assertThat(afterDelta.index).isEqualTo(tickers[0].index).isNotNull
        assertThat(afterDelta.timeMs).isEqualTo(1_791_240_109_762L)
        val last = tickers[2]
        assertThat(last.bid).isEqualByComparingTo("85930.6")
        assertThat(last.bidSize).isEqualByComparingTo("0.009")
        assertThat(last.ask).isEqualByComparingTo("85931.2")
        assertThat(last.mark).isEqualTo(tickers[0].mark)
        stream.close()
    }

    @Test
    fun `a spot pair takes its sides from the book, as its ticker carries none`() {
        val stream = stream(contracts = false)
        stream.start()
        until { links.contains(true) }
        stream.quote(listOf("BTCUSDT"))
        sent.poll(5, TimeUnit.SECONDS)

        venue!!.send(Fixtures.text("ws-spot-ticker.json"))
        venue!!.send(Fixtures.text("ws-spot-orderbook-1.json"))
        until { tickers.size == 2 }

        assertThat(tickers[0].bid).isNull()
        assertThat(tickers[1].bid).isEqualByComparingTo("85907.5")
        assertThat(tickers[1].ask).isEqualByComparingTo("85907.6")
        assertThat(tickers[1].last).isEqualByComparingTo("85907.6")
        stream.close()
    }

    @Test
    fun `a taped contract hears its trades and its liquidations`() {
        val stream = stream(contracts = true)
        stream.tape(listOf("BTCUSDT"))
        stream.start()
        until { links.contains(true) }
        assertThat(
            sent.poll(5, TimeUnit.SECONDS),
        ).isEqualTo("""{"op":"subscribe","args":["publicTrade.BTCUSDT","allLiquidation.BTCUSDT"]}""")

        venue!!.send(Fixtures.text("ws-linear-public-trade.json"))
        venue!!.send(Fixtures.text("ws-linear-all-liquidation.json"))
        until { trades.size == 1 && liquidations.size == 1 }

        val trade = trades.single().second.single()
        assertThat(trade.id).isEqualTo("c9973f17-3657-54b1-b419-3690be7a964b")
        assertThat(trade.timeMs).isEqualTo(1_791_240_109_704L)
        assertThat(trade.price).isEqualByComparingTo("86346.90")
        assertThat(trade.side).isEqualTo("Sell")
        val liquidated = liquidations.single().second
        assertThat(liquidated.map { it.size }).containsExactly(BigDecimal("0.015"), BigDecimal("0.130"))
        assertThat(liquidated.map { it.id }).doesNotHaveDuplicates()
        assertThat(liquidated.map { it.side }).containsOnly("Sell")
        stream.close()
    }

    @Test
    fun `a taped spot pair hears its trades only, as spot has no liquidation feed`() {
        val stream = stream(contracts = false)
        stream.tape(listOf("BTCUSDT"))
        stream.start()

        assertThat(sent.poll(5, TimeUnit.SECONDS)).isEqualTo("""{"op":"subscribe","args":["publicTrade.BTCUSDT"]}""")
        stream.close()
    }

    @Test
    fun `more than ten topics go in requests of ten, as spot allows no more`() {
        val stream = stream(contracts = false)
        stream.quote((1..6).map { "C${it}USDT" })
        stream.start()

        assertThat(sent.poll(5, TimeUnit.SECONDS)!!.split(',').size).isEqualTo(10 + 1)
        assertThat(sent.poll(5, TimeUnit.SECONDS)!!.split(',').size).isEqualTo(2 + 1)
        stream.close()
    }
}

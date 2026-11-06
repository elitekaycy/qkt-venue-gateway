package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import com.qkt.venuegateway.testkit.RecordingListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitAdapterStreamsTest {
    @TempDir lateinit var dir: Path
    private val venues = mutableListOf<BybitVenue>()
    private val socketServer = MockWebServer().apply { start() }
    private val frames = LinkedBlockingQueue<String>()

    @AfterEach
    fun stop() {
        venues.forEach { it.close() }
        socketServer.close()
    }

    private fun venue(marketUrl: String = BybitVenue.NOWHERE) =
        BybitVenue(dir, marketUrl = marketUrl).also { venues += it }

    /** A public socket that keeps every frame the adapter sends it. */
    private fun publicSocket(): String {
        socketServer.enqueue(
            MockResponse
                .Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) = Unit

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            frames += text
                        }
                    },
                ).build(),
        )
        return socketServer.url("/v5/public/linear").toString().replace("http", "ws")
    }

    @Test
    fun `account pushes reach the listener as orders, fills and funding once it is connected`() {
        val venue = venue()
        val listener = RecordingListener()
        venue.onOrder(BybitVenue.order("private/ws-order-limit-new.json"))
        venue.adapter.connect(listener)

        venue.onOrder(BybitVenue.order("private/ws-order-limit-new.json"))
        venue.onExecution(BybitVenue.execution("private/ws-execution-stop-fired.json"))
        venue.onExecution(BybitVenue.execution("private/ws-execution-funding.json"))
        venue.onAccountLink(false, "bybit account dropped")

        assertThat(listener.orders.map { it.clientOrderId to it.status })
            .containsExactly("rec-rest-1" to OrderStatus.WORKING)
        assertThat(listener.fills.map { it.clientOrderId }).containsExactly("rec-stop-down")
        assertThat(listener.funding.map { it.fundingId }).containsExactly("e7a7a67a-1c62-42d9-aae4-5e06a9692f06")
        assertThat(listener.connections).containsExactly(false)
    }

    @Test
    fun `tickers reach the listener as quotes and the public link as the quote feed`() {
        val venue = venue()
        val listener = RecordingListener()
        venue.adapter.connect(listener)
        val item = BybitVenue.items("ticker-linear-BTCUSDT.json").first()

        venue.onTicker(BybitMarketJson.ticker(item, 1_791_239_689_155))
        venue.onMarketLink(false, "bybit market dropped")

        val quote = listener.quotes.single()
        assertThat(quote.symbol).isEqualTo("BTCUSDT")
        assertThat(quote.bid).isEqualByComparingTo("87717.60")
        assertThat(quote.ask).isEqualByComparingTo("87717.90")
        assertThat(listener.quoteFeed).containsExactly(false)
    }

    @Test
    fun `codes taped before a restart are taped again, and only listed codes are quoted`() {
        Files.writeString(dir.resolve("taped.txt"), "BTCUSDT\n")
        val venue = venue(publicSocket())
        val listener = RecordingListener()

        venue.adapter.connect(listener)
        listener.await("the public link to come up", 10_000) { listener.quoteFeed.contains(true) }
        venue.adapter.subscribeQuotes(setOf("BTCUSDT", "NOPEUSDT"))

        val sent = generateSequence { frames.poll(5, TimeUnit.SECONDS) }.take(2).toList()
        val topics = sent.map { Json.parseToJsonElement(it).jsonObject["args"].toString() }
        assertThat(topics[0]).contains("publicTrade.BTCUSDT", "allLiquidation.BTCUSDT")
        assertThat(topics[1]).contains("tickers.BTCUSDT", "orderbook.1.BTCUSDT").doesNotContain("NOPEUSDT")
    }

    @Test
    fun `a tape that cannot be resumed does not stop the adapter connecting`() {
        Files.createDirectories(dir.resolve("taped.txt"))
        val venue = venue()
        val listener = RecordingListener()

        assertThatCode { venue.adapter.connect(listener) }.doesNotThrowAnyException()
        venue.onAccountLink(true, "bybit account open")
        assertThat(listener.connections).containsExactly(true)
    }
}

package com.qkt.venuegateway.deribit.client

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DeribitTickerStreamTest {
    private val server = MockWebServer().apply { start() }
    private val requests = LinkedBlockingQueue<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val data =
        javaClass
            .getResource(
                "/fixtures/ticker-option.json",
            )!!
            .readText()
            .substringAfter("\"result\":")
            .substringBeforeLast(",\"usIn\"")

    @AfterEach
    fun stop() = server.close()

    private fun socket() =
        MockResponse
            .Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        sockets += webSocket
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        requests += text
                    }
                },
            ).build()

    @Test
    fun `it subscribes the wanted tickers, parses notifications, and subscribes again after a drop`() {
        server.enqueue(socket())
        server.enqueue(socket())
        val tickers = LinkedBlockingQueue<DeribitTicker>()
        val stream =
            DeribitTickerStream(server.url("/ws").toString().replace("http", "ws"), { tickers += it }, { _, _ -> })
        stream.subscribe(setOf("BTC_USDC-2OCT26-65000-C"))
        stream.start()

        val subscribe = requests.poll(5, TimeUnit.SECONDS)
        assertThat(subscribe).contains("\"method\":\"public/subscribe\"", "\"ticker.BTC_USDC-2OCT26-65000-C.100ms\"")
        sockets.single().send(
            """{"jsonrpc":"2.0","method":"subscription","params":{"channel":"ticker.BTC_USDC-2OCT26-65000-C.100ms","data":$data}}""",
        )
        assertThat(tickers.poll(5, TimeUnit.SECONDS)!!.bid).isEqualByComparingTo("18740")

        sockets.single().close(1001, "going away")

        assertThat(requests.poll(10, TimeUnit.SECONDS)).contains("public/subscribe", "BTC_USDC-2OCT26-65000-C")
        stream.close()
    }
}

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

class DeribitTickerStreamArmsTest {
    private val server = MockWebServer().apply { start() }
    private val requests = LinkedBlockingQueue<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val links = CopyOnWriteArrayList<Boolean>()
    private val data =
        javaClass
            .getResource("/fixtures/ticker-option.json")!!
            .readText()
            .substringAfter("\"result\":")
            .substringBeforeLast(",\"usIn\"")

    @AfterEach
    fun stop() = server.close()

    private fun stream(tickers: MutableList<DeribitTicker>): DeribitTickerStream {
        server.enqueue(
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
                ).build(),
        )
        return DeribitTickerStream(
            server.url("/ws").toString().replace("http", "ws"),
            { tickers += it },
            { up, _ -> links += up },
        )
    }

    private fun until(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `nothing wanted subscribes nothing, and a change subscribes what is added and drops what is not`() {
        val stream = stream(CopyOnWriteArrayList())
        stream.start()
        until { sockets.isNotEmpty() && links.contains(true) }
        assertThat(requests.poll(400, TimeUnit.MILLISECONDS)).describedAs("nothing wanted yet").isNull()

        stream.subscribe(setOf("BTC_USDC-PERPETUAL"))
        assertThat(requests.poll(5, TimeUnit.SECONDS)).contains("public/subscribe", "BTC_USDC-PERPETUAL")
        stream.subscribe(setOf("ETH_USDC-PERPETUAL"))
        val first = requests.poll(5, TimeUnit.SECONDS)!!
        val second = requests.poll(5, TimeUnit.SECONDS)!!
        stream.subscribe(setOf("ETH_USDC-PERPETUAL"))

        assertThat(listOf(first, second).filter { it.contains("public/subscribe") }.single()).contains("ETH_USDC")
        assertThat(listOf(first, second).filter { it.contains("public/unsubscribe") }.single()).contains("BTC_USDC")
        assertThat(requests.poll(400, TimeUnit.MILLISECONDS)).describedAs("nothing changed").isNull()
        stream.close()
    }

    @Test
    fun `a notification of another channel is ignored and the ticker after it is read`() {
        val tickers = CopyOnWriteArrayList<DeribitTicker>()
        val stream = stream(tickers)
        stream.start()
        until { sockets.isNotEmpty() && links.contains(true) }

        sockets.single().send(
            """{"jsonrpc":"2.0","method":"subscription","params":{"channel":"book.X.raw","data":{}}}""",
        )
        sockets.single().send(
            """{"jsonrpc":"2.0","method":"subscription","params":{"channel":"ticker.X.100ms","data":$data}}""",
        )
        until { tickers.isNotEmpty() }

        assertThat(tickers).hasSize(1)
        stream.close()
    }
}

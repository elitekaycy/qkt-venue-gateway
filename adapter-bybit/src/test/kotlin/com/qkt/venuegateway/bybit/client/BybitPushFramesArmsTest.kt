package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.Fixtures
import com.qkt.venuegateway.bybit.with
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitPushFramesArmsTest {
    private val server = MockWebServer().apply { start() }
    private var venue: WebSocket? = null
    private val links = CopyOnWriteArrayList<Boolean>()
    private val tickers = CopyOnWriteArrayList<BybitTicker>()
    private val pushes = CopyOnWriteArrayList<String>()
    private val orders = CopyOnWriteArrayList<BybitOrder>()
    private val url get() = server.url("/v5").toString().replace("http", "ws")

    @AfterEach
    fun stop() = server.close()

    private fun frame(name: String): JsonObject = Json.parseToJsonElement(Fixtures.text(name)).jsonObject

    private fun serve(
        onOpen: (WebSocket) -> Unit = {},
        onText: (WebSocket, String) -> Unit = { _, _ -> },
    ) = server.enqueue(
        MockResponse
            .Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        venue = webSocket
                        onOpen(webSocket)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) = onText(webSocket, text)
                },
            ).build(),
    )

    private fun until(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun market(): BybitMarketStream =
        BybitMarketStream(url, true, { tickers += it }, { _, _ -> }, { _, _ -> }) { up, _ -> links += up }

    @Test
    fun `asking again for topics already wanted subscribes nothing more`() {
        val subscribed = CopyOnWriteArrayList<String>()
        serve(onText = { _, text -> subscribed += text })
        val stream = market()
        stream.start()
        until { links.contains(true) }

        stream.quote(listOf("BTCUSDT"))
        until { subscribed.size == 1 }
        stream.quote(listOf("BTCUSDT"))
        Thread.sleep(300)

        assertThat(subscribed).hasSize(1)
        stream.close()
    }

    @Test
    fun `an account push with no data array, or for another category, reaches no handler`() {
        serve(onText = { socket, text ->
            if (text.contains("\"auth\"")) socket.send(Fixtures.text("ws-private-auth.json"))
        })
        val stream =
            BybitAccountStream(url, "k", "s", { 1L }, "linear", { orders += it }, { }) { up, _ -> links += up }
        stream.start()
        until { links.contains(true) }
        val order = frame("private/ws-order-stop-triggered.json")

        venue!!.send(JsonObject(order + ("data" to JsonObject(emptyMap()))).toString())
        venue!!.send(order.with("topic" to "order.spot").toString())
        venue!!.send(order.toString())
        until { orders.isNotEmpty() }

        assertThat(orders.map { it.orderLinkId }).containsExactly("rec-stop-down")
        stream.close()
    }

    @Test
    fun `a subscription confirmed, a pong, and a frame naming no success are acknowledged without a push`() {
        serve(onText = { socket, text ->
            if (text.contains("subscribe")) {
                socket.send(Fixtures.text("ws-private-subscribe.json"))
                socket.send(Fixtures.text("ws-private-pong.json"))
                socket.send("""{"op":"subscribe","ret_msg":"no success field"}""")
                socket.send("""{"op":"subscribe","success":{"x":1}}""")
                socket.send(Fixtures.text("ws-linear-public-trade.json"))
            }
        })
        val socket =
            BybitSocket(
                url,
                "market",
                { listOf("publicTrade.BTCUSDT") },
                { topic, _ -> pushes += topic },
                { up, _ -> links += up },
            )
        socket.start()

        until { pushes.isNotEmpty() }

        assertThat(pushes).containsExactly("publicTrade.BTCUSDT")
        socket.close()
    }
}

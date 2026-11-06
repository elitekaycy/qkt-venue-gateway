package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.Fixtures
import com.qkt.venuegateway.bybit.with
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class BybitStreamArmsTest {
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
    fun `a refused subscription is logged and the link goes on serving what it was given`() {
        serve(onText = { socket, text ->
            if (text.contains("subscribe")) {
                socket.send("""{"success":false,"ret_msg":"error:handler not found","op":"subscribe"}""")
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
        assertThat(links).containsExactly(true)
        socket.close()
    }

    @Test
    fun `a link that drops while up is reported down, and one the gateway closed is not`() {
        serve(onOpen = { it.close(1000, "restart") })
        serve()
        val stream = market()
        stream.start()

        until { links == listOf(true, false) || links.size >= 3 }
        assertThat(links.take(2)).containsExactly(true, false)

        val reported = links.size
        stream.close()
        Thread.sleep(300)
        assertThat(links.size).describedAs("closing the stream reports nothing").isEqualTo(reported)
    }

    @Test
    fun `a delta before any snapshot starts a ticker of what it carries, and a snapshot starts it afresh`() {
        serve()
        val stream = market()
        stream.quote(listOf("BTCUSDT"))
        stream.start()
        until { links.contains(true) }

        venue!!.send(Fixtures.text("ws-linear-ticker-delta.json"))
        until { tickers.size == 1 }
        venue!!.send(Fixtures.text("ws-linear-ticker-snapshot.json"))
        venue!!.send(Fixtures.text("ws-linear-orderbook-1.json"))
        venue!!.send(Fixtures.text("ws-linear-ticker-snapshot.json"))
        until { tickers.size == 4 }

        assertThat(tickers[0].mark).isNull()
        assertThat(tickers[0].bid).isEqualByComparingTo("86346.80")
        assertThat(tickers[2].bid).isEqualByComparingTo("85930.6")
        assertThat(tickers[3].bid).describedAs("a snapshot replaces what the book set").isEqualByComparingTo("86346.90")
        stream.close()
    }

    @Test
    fun `a frame naming no type is a delta`() {
        serve()
        val stream = market()
        stream.quote(listOf("BTCUSDT"))
        stream.start()
        until { links.contains(true) }

        venue!!.send(Fixtures.text("ws-linear-ticker-snapshot.json"))
        venue!!.send(frame("ws-linear-ticker-delta.json").with("type" to null).toString())
        until { tickers.size == 2 }

        assertThat(tickers[1].mark).describedAs("kept from the snapshot").isEqualTo(tickers[0].mark).isNotNull
        stream.close()
    }

    @Test
    fun `a book with no bids or no asks leaves that side empty`() {
        serve()
        val stream = market()
        stream.quote(listOf("BTCUSDT"))
        stream.start()
        until { links.contains(true) }
        val book = frame("ws-linear-orderbook-1.json")
        val data = book["data"]!!.jsonObject
        val bare = JsonObject(book + ("data" to JsonObject(data + ("b" to JsonArray(emptyList())))))
        val asksBare = JsonObject(book + ("data" to JsonObject(data + ("a" to JsonArray(emptyList())))))

        venue!!.send(bare.toString())
        venue!!.send(asksBare.toString())
        until { tickers.size == 2 }

        assertThat(listOf(tickers[0].bid, tickers[0].bidSize)).containsOnlyNulls()
        assertThat(tickers[0].ask).isEqualByComparingTo("85931.2")
        assertThat(listOf(tickers[1].ask, tickers[1].askSize)).containsOnlyNulls()
        assertThat(tickers[1].bid).isEqualByComparingTo("85930.6")
        stream.close()
    }

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

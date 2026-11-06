package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiSocketTest {
    private val server = Fixtures.Server { _, _, _, _ -> null }
    private val fills = CopyOnWriteArrayList<JsonObject>()
    private val orders = CopyOnWriteArrayList<JsonObject>()
    private val tickers = CopyOnWriteArrayList<JsonObject>()
    private val links = CopyOnWriteArrayList<Boolean>()
    private val socket =
        KalshiSocket(
            server.socketUrl,
            KalshiAuth("key-id", Fixtures.pem),
            { 7L },
            { fills += it },
            { orders += it },
            { tickers += it },
            { up, _ -> links += up },
        )

    @AfterEach
    fun stop() {
        socket.close()
        server.close()
    }

    private fun next() = server.sent.poll(5, TimeUnit.SECONDS)

    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `a connect subscribes the account's fills and orders and the quoted markets, signed on the handshake`() {
        socket.quote(listOf("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30"))
        socket.start()

        assertThat(next()).isEqualTo("""{"id":1,"cmd":"subscribe","params":{"channels":["fill"]}}""")
        assertThat(next()).isEqualTo("""{"id":2,"cmd":"subscribe","params":{"channels":["user_orders"]}}""")
        assertThat(next()).isEqualTo(
            """{"id":3,"cmd":"subscribe","params":{"channels":["ticker"],""" +
                """"market_tickers":["KXNBA3D-26NOV05MIAGSW-GSWSCURRY30"]}}""",
        )
        await { links.contains(true) }
        assertThat(links).containsExactly(true)
    }

    @Test
    fun `pushes reach their handlers as each frame's msg; errors and acks are not pushes`() {
        socket.quote(listOf("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30"))
        socket.start()
        repeat(3) { next() }
        listOf("subscribed-ticker", "error-unknown-channel", "fill", "user-order-resting", "ticker", "trade")
            .forEach { server.push(Fixtures.text("socket/$it.json")) }

        await { tickers.isNotEmpty() }
        assertThat(fills.single()["client_order_id"].toString()).contains("qkt-probe-fill")
        assertThat(orders.single()["status"].toString()).contains("resting")
        assertThat(tickers.single()["market_ticker"].toString()).contains("KXNBA3D")
    }

    @Test
    fun `a new quote set replaces the ticker subscription by its sid`() {
        socket.quote(listOf("A"))
        socket.start()
        repeat(3) { next() }
        await { links.contains(true) }
        server.push(Fixtures.text("socket/subscribed-ticker.json"))
        Thread.sleep(200)

        socket.quote(listOf("B"))

        assertThat(next()).isEqualTo("""{"id":4,"cmd":"unsubscribe","params":{"sids":[3]}}""")
        assertThat(next())
            .isEqualTo("""{"id":5,"cmd":"subscribe","params":{"channels":["ticker"],"market_tickers":["B"]}}""")
    }

    @Test
    fun `no market quoted subscribes no ticker, and a drop is reported`() {
        socket.start()
        assertThat(next()).contains("fill")
        assertThat(next()).contains("user_orders")
        await { links.contains(true) }

        server.sockets.last().close(1000, "bye")

        await { links.contains(false) }
        assertThat(links).startsWith(true, false)
    }
}

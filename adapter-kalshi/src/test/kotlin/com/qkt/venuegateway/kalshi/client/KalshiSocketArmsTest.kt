package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiSocketArmsTest {
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
    private val curry = "KXNBA3D-26NOV05MIAGSW-GSWSCURRY30"

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

    private fun frame(name: String) = Fixtures.json("socket/$name.json")

    private fun started() {
        socket.quote(listOf(curry))
        socket.start()
        repeat(3) { next() }
        await { links.contains(true) }
    }

    @Test
    fun `a push carrying no msg reaches no handler, and the next whole push still does`() {
        started()

        listOf("fill", "user-order-resting", "ticker").forEach { server.push(JsonObject(frame(it) - "msg").toString()) }
        server.push(JsonObject(frame("error-unknown-channel") - "msg").toString())
        server.push(Fixtures.text("socket/fill.json"))
        await { fills.isNotEmpty() }

        assertThat(fills).hasSize(1)
        assertThat(orders).isEmpty()
        assertThat(tickers).isEmpty()
        assertThat(links).containsExactly(true)
    }

    @Test
    fun `only the ack of the ticker request, carrying its sid, is the subscription a later quote replaces`() {
        started()
        val ack = frame("subscribed-ticker")

        server.push(JsonObject(ack - "id").toString())
        server.push(JsonObject(ack + ("id" to JsonPrimitive(99))).toString())
        server.push(JsonObject(ack - "msg").toString())
        Thread.sleep(200)
        socket.quote(listOf(curry))
        val unacked = next()
        server.push(JsonObject(ack + ("id" to JsonPrimitive(4))).toString())
        Thread.sleep(200)
        socket.quote(listOf(curry))
        val replaced = next()

        assertThat(unacked).contains("\"subscribe\"").doesNotContain("unsubscribe")
        assertThat(replaced).contains("\"unsubscribe\"", "\"sids\":[3]")
    }

    @Test
    fun `an error whose msg names no words is logged whole, and the link goes on serving`() {
        started()
        val error = frame("error-unknown-channel")
        val wordless = JsonObject(error + ("msg" to JsonObject(mapOf("code" to JsonPrimitive(8)))))

        server.push(wordless.toString())
        server.push(Fixtures.text("socket/user-order-resting.json"))
        await { orders.isNotEmpty() }

        assertThat(orders).hasSize(1)
        assertThat(links).containsExactly(true)
    }
}

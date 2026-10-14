package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.Fixtures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitAccountStreamTest {
    private val server = MockWebServer().apply { start() }
    private val sent = LinkedBlockingQueue<String>()
    private val links = CopyOnWriteArrayList<Boolean>()

    private fun venue(answerAuth: String?) =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    sent += text
                    if (text.contains("\"auth\"") && answerAuth != null) webSocket.send(answerAuth)
                }

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) = Unit
            },
        )

    private fun stream() =
        BybitAccountStream(
            server.url("/v5/private").toString().replace("http", "ws"),
            "the-key",
            "the-secret",
            { 1_791_240_000_000L },
            "linear",
            {},
            {},
        ) { up, _ -> links += up }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `it signs in with the key and an expiring signature, then subscribes its category's orders and executions`() {
        server.enqueue(venue(Fixtures.text("ws-private-auth.json")))
        val stream = stream()
        stream.start()

        val auth = Json.parseToJsonElement(sent.poll(5, TimeUnit.SECONDS)!!).jsonObject
        val args = auth["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("the-secret".toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("GET/realtime1791240010000".toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(args).containsExactly("the-key", "1791240010000", expected)
        assertThat(
            sent.poll(5, TimeUnit.SECONDS),
        ).isEqualTo("""{"op":"subscribe","args":["order.linear","execution.linear"]}""")
        val deadline = System.currentTimeMillis() + 5_000
        while (!links.contains(true) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(links).containsExactly(true)
        stream.close()
    }

    @Test
    fun `a link whose sign-in bybit does not accept never comes up, subscribes nothing and signs in again`() {
        server.enqueue(venue("""{"success":false,"ret_msg":"Invalid apikey","op":"auth"}"""))
        server.enqueue(venue(null))
        val stream = stream()
        stream.start()

        assertThat(sent.poll(5, TimeUnit.SECONDS)).contains("\"auth\"")
        assertThat(sent.poll(5, TimeUnit.SECONDS)).describedAs("the next link signs in again").contains("\"auth\"")
        assertThat(sent.poll(1, TimeUnit.SECONDS)).isNull()
        assertThat(links).isEmpty()
        stream.close()
    }
}

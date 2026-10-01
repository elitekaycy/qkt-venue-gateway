package com.qkt.venuegateway.deribit.client

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A scripted Deribit: answers each request through [reply], and records what it was sent. */
class DeribitSocketTest {
    private val server = MockWebServer().apply { start() }
    private val sent = LinkedBlockingQueue<JsonObject>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private var reply: (JsonObject) -> String? = { null }
    private val url = server.url("/ws").toString().replace("http", "ws")

    @AfterEach
    fun stop() = server.shutdown()

    private fun venue() =
        MockResponse().withWebSocketUpgrade(
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
                    val request = Json.parseToJsonElement(text).jsonObject
                    sent += request
                    reply(request)?.let(webSocket::send)
                }
            },
        )

    private fun JsonObject.method() = this["method"]!!.jsonPrimitive.content

    private fun JsonObject.id() = this["id"]!!.jsonPrimitive.content

    private fun result(
        request: JsonObject,
        result: String,
    ) = """{"jsonrpc":"2.0","id":${request.id()},"result":$result}"""

    private fun socket(
        heartbeatSeconds: Int? = null,
        timeoutMs: Long = 5_000,
        onOpen: ((String, JsonObject) -> JsonElement) -> Unit = {},
        notified: MutableList<String> = mutableListOf(),
        connections: MutableList<Boolean> = CopyOnWriteArrayList(),
    ) = DeribitSocket(
        url,
        "test",
        heartbeatSeconds,
        timeoutMs,
        { channel, data -> notified += "$channel $data" },
        { up, _ -> connections += up },
        onOpen,
    )

    @Test
    fun `a request gets the response with its id, and a venue error carries its code and reason`() {
        server.enqueue(venue())
        reply = { r ->
            if (r.method() == "private/buy") {
                """{"jsonrpc":"2.0","id":${r.id()},"error":{"code":-32602,"message":"Invalid params",""" +
                    """"data":{"reason":"must be a multiple of contract size","param":"amount"}}}"""
            } else {
                result(r, """{"version":"1.2.26"}""")
            }
        }
        val connections = CopyOnWriteArrayList<Boolean>()
        val socket = socket(connections = connections).also { it.start() }
        awaitTrue { connections.contains(true) }

        val answer = socket.request("public/test")

        assertThat(answer.jsonObject["version"]!!.jsonPrimitive.content).isEqualTo("1.2.26")
        assertThatThrownBy { socket.request("private/buy", JsonObject(mapOf("amount" to JsonPrimitive("1.5")))) }
            .isInstanceOfSatisfying(DeribitException::class.java) {
                assertThat(it.code).isEqualTo(-32602)
                assertThat(it.reason).isEqualTo("must be a multiple of contract size")
            }
        socket.close()
    }

    @Test
    fun `the open hook runs before the link is reported up, on every connect, and a drop fails what was pending`() {
        server.enqueue(venue())
        server.enqueue(venue())
        reply = { r -> if (r.method() == "private/slow") null else result(r, "\"ok\"") }
        val connections = CopyOnWriteArrayList<Boolean>()
        val opened = CopyOnWriteArrayList<String>()
        val socket =
            socket(
                timeoutMs = 30_000,
                onOpen = { call -> opened += call("public/auth", JsonObject(emptyMap())).jsonPrimitive.content },
                connections = connections,
            )
        socket.start()
        awaitTrue { connections == listOf(true) }
        assertThat(opened).containsExactly("ok")

        val pending =
            Thread {
                runCatching { socket.request("private/slow") }.onFailure {
                    opened +=
                        it.javaClass.simpleName
                }
            }
        pending.start()
        awaitTrue { sent.any { it.method() == "private/slow" } }
        sockets.first().close(1001, "going away")

        awaitTrue { connections == listOf(true, false, true) }
        pending.join(5_000)
        assertThat(opened).containsExactly("ok", IOException::class.java.simpleName, "ok")
        socket.close()
    }

    @Test
    fun `a heartbeat is set on open and every test request is answered, and notifications reach the handler`() {
        server.enqueue(venue())
        reply = { r -> result(r, "\"ok\"") }
        val notified = CopyOnWriteArrayList<String>()
        val connections = CopyOnWriteArrayList<Boolean>()
        val socket = socket(heartbeatSeconds = 10, notified = notified, connections = connections).also { it.start() }
        awaitTrue { connections.contains(true) }

        val heartbeat = sent.poll(5, TimeUnit.SECONDS)!!
        assertThat(heartbeat.method()).isEqualTo("public/set_heartbeat")
        assertThat(heartbeat["params"]!!.jsonObject["interval"]!!.jsonPrimitive.content).isEqualTo("10")
        sockets.single().send("""{"jsonrpc":"2.0","method":"heartbeat","params":{"type":"test_request"}}""")
        assertThat(sent.poll(5, TimeUnit.SECONDS)!!.method()).isEqualTo("public/test")
        sockets.single().send(
            """{"jsonrpc":"2.0","method":"subscription","params":{"channel":"user.orders.future.USDC.raw","data":{"a":1}}}""",
        )
        awaitTrue { notified.isNotEmpty() }
        assertThat(notified).containsExactly("""user.orders.future.USDC.raw {"a":1}""")
        socket.close()
    }

    @Test
    fun `a request the venue never answers times out as unreachable, and one sent while down fails at once`() {
        server.enqueue(venue())
        val connections = CopyOnWriteArrayList<Boolean>()
        val socket = socket(timeoutMs = 300, connections = connections)

        assertThatThrownBy { socket.request("public/test") }.isInstanceOf(IOException::class.java)
        socket.start()
        awaitTrue { connections.contains(true) }
        assertThatThrownBy { socket.request("public/test") }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("no answer")
        socket.close()
    }

    @Test
    fun `nothing but the open hook can call the venue until the hook has finished`() {
        server.enqueue(venue())
        reply = { r -> result(r, "\"ok\"") }
        val hookRunning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connections = CopyOnWriteArrayList<Boolean>()
        val opening: ((String, JsonObject) -> JsonElement) -> Unit = { call ->
            call("public/auth", JsonObject(emptyMap()))
            hookRunning.countDown()
            release.await()
        }
        val socket = socket(onOpen = opening, connections = connections).also { it.start() }
        hookRunning.await(5, TimeUnit.SECONDS)

        assertThatThrownBy { socket.request("private/buy") }.hasMessageContaining("not connected")
        release.countDown()
        awaitTrue { connections.contains(true) }
        assertThat(socket.request("private/buy").jsonPrimitive.content).isEqualTo("ok")
        socket.close()
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}

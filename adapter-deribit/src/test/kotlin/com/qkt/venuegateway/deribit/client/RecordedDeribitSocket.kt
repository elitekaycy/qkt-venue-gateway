package com.qkt.venuegateway.deribit.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Deribit's private socket on a local server. A method named in [answers] is answered with the response recorded in
 * that fixture, under the request's own id; the subscription confirms every channel asked and anything else is `ok`.
 */
internal class RecordedDeribitSocket : AutoCloseable {
    private val server = MockWebServer().apply { start() }
    val sent = LinkedBlockingQueue<JsonObject>()
    val sockets = CopyOnWriteArrayList<WebSocket>()
    val answers = ConcurrentHashMap<String, String>()
    val connections = CopyOnWriteArrayList<Boolean>()

    fun recorded(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText()).jsonObject

    /** A client connected to this socket, what it sent while connecting already taken. */
    fun connected(
        onOrder: (DeribitOrder) -> Unit = {},
        onTrade: (DeribitTrade) -> Unit = {},
    ): DeribitPrivateClient {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(Venue()).build())
        val client =
            DeribitPrivateClient(
                server.url("/ws").toString().replace("http", "ws"),
                "id-7",
                "secret-7",
                setOf("USDC"),
                onOrder,
                onTrade,
                { up, _ -> connections += up },
            )
        client.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (!connections.contains(true)) {
            check(System.currentTimeMillis() < deadline) { "timed out connecting" }
            Thread.sleep(10)
        }
        sent.clear()
        return client
    }

    /** The next request the client sent calling [method], skipping any other. */
    fun next(method: String): JsonObject {
        while (true) {
            val request = sent.poll(5, TimeUnit.SECONDS) ?: error("no $method was sent")
            if (request["method"]!!.jsonPrimitive.content == method) return request
        }
    }

    override fun close() = server.close()

    private fun answer(request: JsonObject): JsonObject {
        val id = request["id"]!!
        val method = request["method"]!!.jsonPrimitive.content
        answers[method]?.let { return JsonObject(recorded(it)["response"]!!.jsonObject + ("id" to id)) }
        val result: JsonElement =
            if (method == "private/subscribe") request["params"]!!.jsonObject["channels"]!! else JsonPrimitive("ok")
        return JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to id, "result" to result))
    }

    private inner class Venue : WebSocketListener() {
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
            webSocket.send(answer(request).toString())
        }
    }
}

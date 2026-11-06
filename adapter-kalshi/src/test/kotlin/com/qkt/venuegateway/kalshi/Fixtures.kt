package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.kalshi.client.KalshiMarket
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Kalshi's recorded answers (`src/test/resources/fixtures`), and a server that answers with them. */
object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java
            .getResource("/fixtures/$name")
            ?.readText()
            ?.trim() ?: error("no fixture $name")

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    /** The [n]th market of the first event of the events page [name]. */
    fun market(
        name: String,
        n: Int = 0,
    ): KalshiMarket = KalshiMarketJson.event(json(name)["events"]!!.jsonArray[0].jsonObject).markets[n]

    /** A fresh Ed25519 key as Kalshi issues it: a PKCS#8 PEM. */
    val pem: String by lazy {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    /**
     * A Kalshi stand-in: each HTTP request is answered with the fixture [route] names for its method, path and query
     * and body (HTTP 404 with Kalshi's recorded `not_found` when none); a name `!<status> <file>` answers that
     * file with that status (an answer recorded with a status other than 200). `/trade-api/ws/v2` upgrades to a
     * socket whose frames from the client land in [sent]; [push] sends one to it.
     */
    class Server(
        private val route: (method: String, path: String, query: Map<String, String>, body: String) -> String?,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val sent = LinkedBlockingQueue<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        private val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val url = request.url
                            if (url.encodedPath == SOCKET) {
                                return MockResponse.Builder().webSocketUpgrade(Listener()).build()
                            }
                            requests += request
                            val query = url.queryParameterNames.associateWith { url.queryParameter(it)!! }
                            val body = request.body?.utf8() ?: ""
                            val name = route(request.method, url.encodedPath.removePrefix(API), query, body)
                            val (status, file) = name?.let(::statusOf) ?: (404 to "private/error-not-found.json")
                            return MockResponse(code = status, body = text(file))
                        }
                    }
                start()
            }

        /** The REST base, as `https://…/trade-api/v2` is given to the adapter. */
        val restUrl: String get() = server.url(API).toString()

        val socketUrl: String get() = server.url(SOCKET).toString().replace("http", "ws")

        fun push(frame: String) = sockets.last().send(frame)

        override fun close() = server.close()

        private fun statusOf(name: String): Pair<Int, String> =
            if (name.startsWith("!")) name.substring(1, 4).toInt() to name.substring(5) else 200 to name

        private inner class Listener : WebSocketListener() {
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
                sent += text
            }
        }
    }

    private const val API = "/trade-api/v2"
    private const val SOCKET = "/trade-api/ws/v2"
}

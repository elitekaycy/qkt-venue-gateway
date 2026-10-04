package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The stream and listing gaps of issue #20: another stream's `since`, tokens on WebSockets, other tools' orders. */
class StreamGapsTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private lateinit var gateway: Gateway
    private lateinit var server: GatewayServer
    private var base = ""

    @BeforeEach
    fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1_000L },
                mapOf(Role.TRADER to "t-token"),
            ) { 1_000L }
        gateway.start()
        venue.listener!!.connection(true, "test")
        server = GatewayServer(gateway, QuoteHub(gateway).also { it.start() }, "127.0.0.1", 0)
        base = "http://127.0.0.1:${server.start()}"
    }

    @AfterEach
    fun stop() {
        server.close()
        gateway.close()
    }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
    ): String {
        val request =
            Request
                .Builder()
                .url(base + path)
                .method(method, body?.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer t-token")
                .build()
        return http.newCall(request).execute().use { it.body!!.string() }
    }

    private fun order(id: String) {
        call(
            "POST",
            "/v1/orders",
            """{"client_order_id":"$id","symbol":"BTC_USDC-PERPETUAL","side":"buy","type":"limit",""" +
                """"quantity":"0.1","limit_price":"50000","time_in_force":"gtc","reduce_only":false}""",
        )
    }

    /** Opens [path] with [token]; the messages arrive on the queue, a refused upgrade's status on the future. */
    private fun socket(
        path: String,
        token: String = "t-token",
    ): Pair<LinkedBlockingQueue<String>, CompletableFuture<Int>> {
        val messages = LinkedBlockingQueue<String>()
        val refused = CompletableFuture<Int>()
        http.newWebSocket(
            Request
                .Builder()
                .url(base.replace("http", "ws") + path)
                .header("Authorization", "Bearer $token")
                .build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    messages += text
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    refused.complete(response?.code ?: -1)
                }
            },
        )
        return messages to refused
    }

    @Test
    fun `a since from another stream gets a reset even when it is not beyond the log`() {
        order("a-1.x")
        order("a-2.x")

        val (foreign, _) = socket("/v1/stream?since=1&stream=an-old-stream")
        val (own, _) = socket("/v1/stream?since=1&stream=${gateway.journal.stream}")

        assertThat(foreign.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"reset\"", "\"seq\":2")
        assertThat(own.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"order\"", "\"seq\":2")
    }

    @Test
    fun `a wrong token on the stream or the quotes is refused with 401 before the upgrade`() {
        val (_, stream) = socket("/v1/stream", token = "wrong")
        val (_, quotes) = socket("/v1/quotes?symbols=BTC_USDC-PERPETUAL", token = "wrong")

        assertThat(stream.get(5, TimeUnit.SECONDS)).isEqualTo(401)
        assertThat(quotes.get(5, TimeUnit.SECONDS)).isEqualTo(401)
    }

    @Test
    fun `the working orders list only the orders this gateway placed, not another tool's on the account`() {
        order("a-1.x")
        venue.orders["other-tool-7"] = venue.orders.getValue("a-1.x").copy(clientOrderId = "other-tool-7")

        val listed = call("GET", "/v1/orders")

        assertThat(listed).contains("\"client_order_id\":\"a-1.x\"").doesNotContain("other-tool-7")
    }
}

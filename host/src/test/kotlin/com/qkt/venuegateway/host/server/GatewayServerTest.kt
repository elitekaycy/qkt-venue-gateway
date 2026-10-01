package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import java.math.BigDecimal
import java.nio.file.Path
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GatewayServerTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val tokens = mapOf(Role.TRADER to "t-token", Role.GUARDIAN to "g-token")
    private val http = OkHttpClient()
    private lateinit var gateway: Gateway
    private lateinit var server: GatewayServer
    private var base = ""

    private fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) {
                    1_000L
                },
                tokens,
            ) { 1_000L }
        gateway.start()
        venue.listener!!.connection(true, "test")
        server =
            GatewayServer(
                gateway,
                com.qkt.venuegateway.host.market
                    .QuoteHub(gateway)
                    .also { it.start() },
                "127.0.0.1",
                0,
            )
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
        token: String? = "t-token",
        body: String? = null,
    ): Pair<Int, String> {
        val request =
            Request
                .Builder()
                .url(base + path)
                .method(method, body?.toRequestBody("application/json".toMediaType()))
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
        return http.newCall(request).execute().use { it.code to it.body!!.string() }
    }

    private fun submit(
        id: String,
        side: String = "buy",
        reduceOnly: Boolean = false,
    ) = """{"client_order_id":"$id","symbol":"BTC_USDC-PERPETUAL","side":"$side","type":"market","quantity":"0.1",""" +
        """"time_in_force":"gtc","reduce_only":$reduceOnly}"""

    private fun stream(since: Long?): LinkedBlockingQueue<String> {
        val messages = LinkedBlockingQueue<String>()
        val url = base.replace("http", "ws") + "/v1/stream" + (since?.let { "?since=$it" } ?: "")
        http.newWebSocket(
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer t-token")
                .build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    messages += text
                }

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {}
            },
        )
        return messages
    }

    @Test
    fun `health needs a token and anchors the stream, and orders are idempotent with dead ids written off`() {
        start()
        assertThat(call("GET", "/v1/health", token = null).first).isEqualTo(401)
        assertThat(
            call("GET", "/v1/health").second,
        ).contains("\"protocol\":\"vgp1\"", "\"account_login\":\"7\"", "\"seq\":0")

        assertThat(call("POST", "/v1/orders", body = submit("a-1.x")).first).isEqualTo(201)
        assertThat(call("POST", "/v1/orders", body = submit("a-1.x")).first).isEqualTo(200)
        assertThat(call("GET", "/v1/orders/a-9.x").first).isEqualTo(404)
        assertThat(call("POST", "/v1/orders", body = submit("a-9.x")).second).contains("\"code\":\"conflict\"")
        assertThat(call("POST", "/v1/orders", body = "{not json").first).isEqualTo(400)
        assertThat(call("DELETE", "/v1/orders/a-1.x").second).contains("\"status\":\"cancelled\"")
    }

    @Test
    fun `the stream replays after since, pushes new events live, and resets a foreign since`() {
        start()
        call("POST", "/v1/orders", body = submit("a-1.x"))
        val live = stream(0)
        assertThat(live.poll(5, TimeUnit.SECONDS)).contains("\"seq\":1", "\"type\":\"order\"")

        venue.listener!!.fill(
            VenueFill("a-1.x", "v-1", "f1", "BTC_USDC-PERPETUAL", Side.BUY, BigDecimal("0.1"), BigDecimal("84000"), 5),
        )

        assertThat(live.poll(5, TimeUnit.SECONDS)).contains("\"seq\":2", "\"type\":\"fill\"")
        assertThat(stream(1).poll(5, TimeUnit.SECONDS)).contains("\"seq\":2")
        assertThat(stream(99).poll(5, TimeUnit.SECONDS)).contains("\"type\":\"reset\"", "\"seq\":2")
    }

    @Test
    fun `only the guardian flips the kill switch, which then refuses orders that add risk`() {
        start()
        val all = """{"scope":"all"}"""
        assertThat(call("POST", "/v1/kill", body = all).first).isEqualTo(401)
        assertThat(call("POST", "/v1/kill", token = "g-token", body = all).second).contains("\"all\":true")

        assertThat(call("POST", "/v1/orders", body = submit("a-1.x")).second).contains("\"code\":\"kill_switch\"")
        assertThat(call("POST", "/v1/kill/release", token = "g-token", body = all).second).contains("\"all\":false")
        assertThat(call("POST", "/v1/orders", body = submit("a-2.x")).first).isEqualTo(201)
    }

    @Test
    fun `one instrument is served by its code, and a code neither the venue nor the shelf holds is not found`() {
        start()

        val (status, body) = call("GET", "/v1/instruments/BTC_USDC-9OCT26-82000-P")
        val (missing, error) = call("GET", "/v1/instruments/BTC_USDC-1JAN20-1-C")

        assertThat(status).isEqualTo(200)
        assertThat(body).contains("\"code\":\"BTC_USDC-9OCT26-82000-P\"", "\"right\":\"put\"")
        assertThat(missing).isEqualTo(404)
        assertThat(error).contains("not_found")
    }

    @Test
    fun `an order is changed by the trader, and a position is closed by the trader or the guardian`() {
        start()
        call("POST", "/v1/orders", body = submit("p-1").replace("\"market\"", "\"limit\",\"limit_price\":\"50000\""))
        venue.net["BTC_USDC-PERPETUAL"] = BigDecimal("0.2")

        val (changed, order) = call("PATCH", "/v1/orders/p-1", body = """{"limit_price":"49000"}""")
        val (guardianPatch, _) = call("PATCH", "/v1/orders/p-1", token = "g-token", body = """{"limit_price":"1"}""")
        val (closed, closing) =
            call(
                "POST",
                "/v1/positions/close",
                token = "g-token",
                body = """{"symbol":"BTC_USDC-PERPETUAL"}""",
            )

        assertThat(changed).isEqualTo(200)
        assertThat(order).contains("\"limit_price\":\"49000\"")
        assertThat(guardianPatch).isEqualTo(401)
        assertThat(closed).isEqualTo(200)
        assertThat(closing).contains("\"side\":\"sell\"", "\"reduce_only\":true", "\"quantity\":\"0.2\"")
    }
}

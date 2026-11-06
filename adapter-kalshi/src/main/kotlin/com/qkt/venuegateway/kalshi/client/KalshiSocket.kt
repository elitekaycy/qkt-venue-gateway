package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.support.JsonReads.objOrNull
import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.ReconnectingSocket
import com.qkt.venuegateway.support.SocketLink
import com.qkt.venuegateway.support.SocketProtocol
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * Kalshi's WebSocket at [url] (`wss://…/trade-api/ws/v2`). Kalshi authenticates the handshake itself, so every
 * connect is signed by [auth] (the path, `GET`, at [clock]'s time). On every connect it subscribes the account's
 * `fill` and `user_orders` channels and, when [tickers] names any, the `ticker` channel of those markets (an empty
 * list would subscribe every market, so none is sent). Kalshi pings every 10 s and the transport answers; the client
 * pings every [PING_MS] and drops a link whose pong does not come. Pushes reach [onFill], [onOrder] and [onTicker]
 * as each frame's `msg`; the link going up and down is [onConnection].
 */
class KalshiSocket(
    url: String,
    private val auth: KalshiAuth,
    private val clock: () -> Long,
    private val onFill: (JsonObject) -> Unit,
    private val onOrder: (JsonObject) -> Unit,
    private val onTicker: (JsonObject) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    http: OkHttpClient = OkHttpClient(),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(KalshiSocket::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger()

    @Volatile private var tickers: List<String> = emptyList()

    @Volatile private var tickerRequest = -1

    @Volatile private var tickerSid: Long? = null

    @Volatile private var stopped = false

    private val signed =
        http
            .newBuilder()
            .pingInterval(Duration.ofMillis(PING_MS))
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                auth.headers(clock(), "GET", request.url.encodedPath).forEach { (k, v) -> builder.header(k, v) }
                chain.proceed(builder.build())
            }.build()
    private val link = ReconnectingSocket(url, "kalshi", Session(), null, signed, clock)

    /** Connects, and keeps the link alive from now on. */
    fun start() = link.start()

    /** Quotes [markets] from now on, replacing the last set; sent now when the link is up, else on the next connect. */
    fun quote(markets: List<String>) {
        tickers = markets
        if (!link.isUp) return
        tickerSid?.let { link.send(command("unsubscribe") { put("sids", JsonArray(listOf(JsonPrimitive(it)))) }) }
        tickerSid = null
        subscribeTickers(link::send)
    }

    override fun close() {
        stopped = true
        link.close()
    }

    private fun subscribeTickers(send: (String) -> Boolean) {
        val markets = tickers
        if (markets.isEmpty()) return
        val id = ids.incrementAndGet()
        tickerRequest = id
        send(subscription(id, "ticker", markets))
    }

    private fun subscription(
        id: Int,
        channel: String,
        markets: List<String> = emptyList(),
    ): String =
        buildJsonObject {
            put("id", id)
            put("cmd", "subscribe")
            put(
                "params",
                buildJsonObject {
                    put("channels", JsonArray(listOf(JsonPrimitive(channel))))
                    if (markets.isNotEmpty()) put("market_tickers", JsonArray(markets.map(::JsonPrimitive)))
                },
            )
        }.toString()

    private fun command(
        cmd: String,
        params: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("id", ids.incrementAndGet())
            put("cmd", cmd)
            put("params", buildJsonObject(params))
        }.toString()

    private fun onText(text: String) {
        val frame = json.parseToJsonElement(text).jsonObject
        val msg = frame.objOrNull("msg")
        when (frame.text("type")) {
            "fill" -> msg?.let(onFill)
            "user_order" -> msg?.let(onOrder)
            "ticker" -> msg?.let(onTicker)
            "subscribed" ->
                if (frame.text("id")?.toInt() == tickerRequest) tickerSid = msg?.text("sid")?.toLong()
            "error" -> log.error("kalshi socket: {}", msg?.text("msg") ?: text)
        }
    }

    /** Kalshi's side of the link: the account channels and the quoted markets, sent on every connect. */
    private inner class Session : SocketProtocol {
        override fun opening(link: SocketLink) {
            tickerSid = null
            link.send(subscription(ids.incrementAndGet(), "fill"))
            link.send(subscription(ids.incrementAndGet(), "user_orders"))
            subscribeTickers(link::send)
        }

        override fun text(frame: String) = onText(frame)

        override fun up() = onConnection(true, "kalshi socket open")

        override fun dropped(
            reason: String,
            wasUp: Boolean,
        ) {
            if (!stopped && wasUp) onConnection(false, reason)
        }
    }

    private companion object {
        const val PING_MS = 20_000L
    }
}

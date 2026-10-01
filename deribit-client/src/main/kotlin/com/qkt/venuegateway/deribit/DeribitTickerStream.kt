package com.qkt.venuegateway.deribit

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/**
 * Deribit's public ticker channels (`ticker.<instrument>.<interval>`) over its JSON-RPC WebSocket at
 * [url]. [subscribe] sets the instruments wanted; every ticker notification reaches [onTicker]. A
 * dropped socket reconnects after a backoff doubling from 1 s to 30 s and subscribes everything again;
 * [onConnection] hears each drop and return.
 */
class DeribitTickerStream(
    private val url: String = "wss://www.deribit.com/ws/api/v2",
    private val onTicker: (DeribitTicker) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val interval: String = "100ms",
) : DeribitTickers {
    private val log = LoggerFactory.getLogger(DeribitTickerStream::class.java)
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong()
    private val timer =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "deribit-tickers").apply {
                isDaemon =
                    true
            }
        }
    private val lock = Any()
    private var wanted: Set<String> = emptySet()
    private var socket: WebSocket? = null
    private var open = false
    private var backoffMs = INITIAL_BACKOFF_MS

    @Volatile private var stopped = false

    override fun start() = connect()

    /** Wants exactly [names]' tickers from now on. */
    override fun subscribe(names: Set<String>) =
        synchronized(lock) {
            val added = names - wanted
            val removed = wanted - names
            wanted = names
            if (open) {
                if (added.isNotEmpty()) send("public/subscribe", added)
                if (removed.isNotEmpty()) send("public/unsubscribe", removed)
            }
        }

    override fun close() {
        stopped = true
        socket?.cancel()
        timer.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun send(
        method: String,
        names: Set<String>,
    ) {
        val channels = names.joinToString(",") { "\"ticker.$it.$interval\"" }
        socket?.send(
            """{"jsonrpc":"2.0","id":${ids.incrementAndGet()},"method":"$method","params":{"channels":[$channels]}}""",
        )
    }

    private fun onText(text: String) {
        val message = json.parseToJsonElement(text) as? JsonObject ?: return
        message["error"]?.let { log.warn("deribit refused a subscription: {}", it) }
        if (message["method"]?.jsonPrimitive?.content != "subscription") return
        val params = message["params"]!!.jsonObject
        if (params["channel"]?.jsonPrimitive?.content?.startsWith("ticker.") != true) return
        onTicker(DeribitJson.ticker(params["data"]!!.jsonObject))
    }

    private fun dropped(reason: String) {
        synchronized(lock) { open = false }
        if (stopped) return
        onConnection(false, reason)
        val delay = synchronized(lock) { backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS) } }
        timer.schedule({ if (!stopped) connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(lock) {
                open = true
                backoffMs = INITIAL_BACKOFF_MS
                if (wanted.isNotEmpty()) send("public/subscribe", wanted)
            }
            onConnection(true, "deribit tickers open")
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            runCatching { onText(text) }.onFailure { log.error("deribit ticker message refused: {}", it.message) }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) = dropped("closed $code $reason")

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) = dropped("failed: ${t.message}")
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val NORMAL_CLOSURE = 1000
    }
}

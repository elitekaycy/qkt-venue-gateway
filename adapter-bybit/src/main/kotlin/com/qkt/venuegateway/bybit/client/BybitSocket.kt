package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.text
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/**
 * One Bybit v5 socket at [url]. On every connect it first sends [auth]'s frame, when given, and waits for
 * Bybit to accept it, then subscribes [topics] (ten a request, as spot allows no more), and only then is the
 * link up ([onConnection] `true`). Pushes (frames with a `topic`) reach [onPush] in arrival order on the
 * reading thread. It pings every 20 s as Bybit asks, and a link silent for [silenceMs] is dropped, as is one
 * whose auth Bybit refuses (a refused subscription is logged; the rest of the link serves). A drop reconnects after a backoff doubling from 1 s to 30 s.
 */
class BybitSocket(
    private val url: String,
    private val name: String,
    private val topics: () -> Collection<String>,
    private val onPush: (topic: String, frame: JsonObject) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val auth: (() -> String)? = null,
    private val silenceMs: Long = 60_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(BybitSocket::class.java)
    private val http = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }
    private val worker =
        Executors.newSingleThreadScheduledExecutor {
            Thread(
                it,
                "bybit-$name",
            ).apply { isDaemon = true }
        }
    private val lock = Any()
    private var socket: WebSocket? = null
    private var up = false
    private var authed = CompletableFuture<Boolean>()
    private var backoffMs = INITIAL_BACKOFF_MS

    @Volatile private var heardMs = System.currentTimeMillis()

    @Volatile private var stopped = false

    /** Connects, and keeps the link alive from now on. */
    fun start() {
        worker.scheduleWithFixedDelay(::keepAlive, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
        connect()
    }

    /** Subscribes [added] now when the link is up; a link that comes up later subscribes [topics] anyway. */
    fun subscribe(added: Collection<String>) {
        val live = synchronized(lock) { socket?.takeIf { up } } ?: return
        added.chunked(TOPICS_PER_REQUEST).forEach { live.send(subscription(it)) }
    }

    override fun close() {
        stopped = true
        synchronized(lock) { socket }?.cancel()
        worker.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        synchronized(lock) { authed = CompletableFuture() }
        http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun opened(webSocket: WebSocket) {
        synchronized(lock) { socket = webSocket }
        heardMs = System.currentTimeMillis()
        val accepted =
            auth?.let {
                webSocket.send(it())
                runCatching { authed.get(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            } ?: true
        if (!accepted) {
            log.error("bybit {}: auth was not accepted", name)
            webSocket.cancel()
            return
        }
        topics().chunked(TOPICS_PER_REQUEST).forEach { webSocket.send(subscription(it)) }
        synchronized(lock) {
            if (socket !== webSocket) return
            up = true
            backoffMs = INITIAL_BACKOFF_MS
        }
        onConnection(true, "bybit $name open")
    }

    private fun keepAlive() {
        val live = synchronized(lock) { socket } ?: return
        if (System.currentTimeMillis() - heardMs > silenceMs) {
            log.warn("bybit {}: nothing heard for {} ms, reconnecting", name, silenceMs)
            live.cancel()
            return
        }
        live.send("""{"op":"ping"}""")
    }

    private fun onText(text: String) {
        heardMs = System.currentTimeMillis()
        val frame = json.parseToJsonElement(text).jsonObject
        val topic = frame.text("topic")
        if (topic != null) return onPush(topic, frame)
        val success = (frame["success"] as? JsonPrimitive)?.content
        when (frame.text("op")) {
            "auth" -> authed.complete(success == "true")
            "subscribe" ->
                if (success ==
                    "false"
                ) {
                    log.error("bybit {}: subscription refused: {}", name, frame.text("ret_msg"))
                }
        }
    }

    private fun dropped(
        webSocket: WebSocket,
        reason: String,
    ) {
        val wasUp =
            synchronized(lock) {
                if (socket != null && socket !== webSocket) return
                socket = null
                authed.complete(false)
                up.also { up = false }
            }
        if (stopped) return
        if (wasUp) onConnection(false, reason)
        val delay = synchronized(lock) { backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS) } }
        worker.schedule({ if (!stopped) connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun subscription(args: List<String>) =
        """{"op":"subscribe","args":${JsonArray(args.map(::JsonPrimitive))}}"""

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            worker.execute { opened(webSocket) }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            runCatching { onText(text) }.onFailure { log.error("bybit {} frame refused: {}", name, it.message) }
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
        ) = dropped(webSocket, "closed $code $reason")

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) = dropped(webSocket, "failed: ${t.message}")
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val PING_MS = 20_000L
        const val AUTH_TIMEOUT_MS = 10_000L
        const val TOPICS_PER_REQUEST = 10
        const val NORMAL_CLOSURE = 1000
    }
}

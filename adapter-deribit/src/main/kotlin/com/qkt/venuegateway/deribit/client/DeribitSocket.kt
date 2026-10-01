package com.qkt.venuegateway.deribit.client

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/**
 * One JSON-RPC session over Deribit's WebSocket at [url]. [request] blocks for the answer with its id
 * (a venue error is [DeribitException]; no link, no answer within [requestTimeoutMs] or a drop is an
 * [IOException]); [send] fires and forgets. On every connect [onOpen] runs first with its own way to
 * call the venue (authenticate, subscribe), and only then is the link up: until it is, [request] fails,
 * so nothing is sent unauthenticated. A failed [onOpen] drops the link. With
 * [heartbeatSeconds] set, Deribit's heartbeat is enabled and each `test_request` answered, or Deribit
 * closes the link. A drop fails every pending request and reconnects after a backoff doubling from 1 s
 * to 30 s. Notifications reach [onNotification] in arrival order on the reading thread, so neither it
 * nor [onConnection] may call [request].
 */
class DeribitSocket(
    private val url: String,
    private val name: String,
    private val heartbeatSeconds: Int? = null,
    private val requestTimeoutMs: Long = 10_000,
    private val onNotification: (channel: String, data: JsonElement) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val onOpen: (call: (method: String, params: JsonObject) -> JsonElement) -> Unit = {},
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(DeribitSocket::class.java)
    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val worker =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "deribit-$name").apply {
                isDaemon =
                    true
            }
        }
    private val lock = Any()
    private var socket: WebSocket? = null
    private var up = false
    private var backoffMs = INITIAL_BACKOFF_MS

    @Volatile private var stopped = false

    fun start() = connect()

    /** Calls [method] with [params] once the link is up, and returns its result. */
    fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = call(method, params, opening = false)

    private fun call(
        method: String,
        params: JsonObject,
        opening: Boolean,
    ): JsonElement {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<JsonElement>().also { pending[id] = it }
        try {
            val live =
                synchronized(lock) { socket?.takeIf { up || opening } }
                    ?: throw IOException("deribit $name is not connected")
            val frame = DeribitFrames.request(id, method, params)
            if (!live.send(frame)) throw IOException("deribit $name refused the frame")
            return answer.get(requestTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("deribit $name: no answer to $method in $requestTimeoutMs ms", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pending.remove(id)
        }
    }

    /** Sends [method] without waiting; a venue error in its answer is logged. */
    fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        synchronized(lock) { socket }?.send(DeribitFrames.request(ids.incrementAndGet(), method, params))
    }

    override fun close() {
        stopped = true
        synchronized(lock) { socket }?.cancel()
        worker.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }

    private fun connect() {
        http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun opened(webSocket: WebSocket) {
        synchronized(lock) { socket = webSocket }
        try {
            val opener = { method: String, params: JsonObject -> call(method, params, opening = true) }
            heartbeatSeconds?.let { opener("public/set_heartbeat", buildJsonObject { put("interval", it) }) }
            onOpen(opener)
        } catch (e: Exception) {
            log.error("deribit {} could not open: {}", name, e.message)
            webSocket.cancel()
            return
        }
        synchronized(lock) {
            if (socket !== webSocket) return
            up = true
            backoffMs = INITIAL_BACKOFF_MS
        }
        onConnection(true, "deribit $name open")
    }

    private fun onText(text: String) {
        when (val frame = DeribitFrames.parse(text)) {
            is DeribitFrame.Answer -> pending[frame.id]?.complete(frame.result)
            is DeribitFrame.Refusal ->
                pending[frame.id]?.completeExceptionally(frame.error)
                    ?: log.warn("deribit {} refused a request: {}", name, frame.error.message)
            is DeribitFrame.Notification -> onNotification(frame.channel, frame.data)
            DeribitFrame.TestRequest -> send("public/test")
            DeribitFrame.Other -> Unit
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
                up.also { up = false }
            }
        pending.values.forEach { it.completeExceptionally(IOException("deribit $name dropped: $reason")) }
        if (stopped) return
        if (wasUp) onConnection(false, reason)
        val delay = synchronized(lock) { backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS) } }
        worker.schedule({ if (!stopped) connect() }, delay, TimeUnit.MILLISECONDS)
    }

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
            runCatching { onText(text) }.onFailure { log.error("deribit {} message refused: {}", name, it.message) }
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
        const val NORMAL_CLOSURE = 1000
    }
}

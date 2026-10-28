package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.support.ReconnectingSocket
import com.qkt.venuegateway.support.SocketLink
import com.qkt.venuegateway.support.SocketProtocol
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * One JSON-RPC session over Deribit's WebSocket at [url]. [request] blocks for the answer with its id
 * (a venue error is [DeribitException]; no link, no answer within [requestTimeoutMs] or a drop is an
 * [IOException]); [send] fires and forgets. On every connect [onOpen] runs first with its own way to
 * call the venue (authenticate, subscribe), and only then is the link up: until it is, [request] fails,
 * so nothing is sent unauthenticated. A failed [onOpen] drops the link. With
 * [heartbeatSeconds] set, Deribit's heartbeat is enabled and each `test_request` answered, or Deribit
 * closes the link. A drop fails every pending request; the link itself ([ReconnectingSocket]) reconnects
 * after a backoff doubling from 1 s to 30 s. Notifications reach [onNotification] in arrival order on the
 * reading thread, so neither it nor [onConnection] may call [request].
 */
class DeribitSocket(
    url: String,
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
    private val link = ReconnectingSocket(url, "deribit-$name", Session(), http = http)

    /** The link being opened or up, for fire-and-forget frames; null between links. */
    @Volatile private var current: SocketLink? = null

    @Volatile private var stopped = false

    fun start() = link.start()

    /** Calls [method] with [params] once the link is up, and returns its result. */
    fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = call(method, params, opening = null)

    private fun call(
        method: String,
        params: JsonObject,
        opening: SocketLink?,
    ): JsonElement {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<JsonElement>().also { pending[id] = it }
        try {
            val frame = DeribitFrames.request(id, method, params)
            val sent =
                if (opening != null) {
                    opening.send(frame)
                } else {
                    if (!link.isUp) throw IOException("deribit $name is not connected")
                    link.send(frame)
                }
            if (!sent) throw IOException("deribit $name refused the frame")
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
        current?.send(DeribitFrames.request(ids.incrementAndGet(), method, params))
    }

    override fun close() {
        stopped = true
        link.close()
        http.dispatcher.executorService.shutdown()
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

    /** Deribit's side of the link: heartbeat and [onOpen] before up, JSON-RPC answers matched by id. */
    private inner class Session : SocketProtocol {
        override fun opening(link: SocketLink) {
            current = link
            val opener = { method: String, params: JsonObject -> call(method, params, opening = link) }
            heartbeatSeconds?.let { opener("public/set_heartbeat", buildJsonObject { put("interval", it) }) }
            onOpen(opener)
        }

        override fun text(frame: String) = onText(frame)

        override fun up() = onConnection(true, "deribit $name open")

        override fun dropped(
            reason: String,
            wasUp: Boolean,
        ) {
            current = null
            pending.values.forEach { it.completeExceptionally(IOException("deribit $name dropped: $reason")) }
            if (!stopped && wasUp) onConnection(false, reason)
        }
    }
}

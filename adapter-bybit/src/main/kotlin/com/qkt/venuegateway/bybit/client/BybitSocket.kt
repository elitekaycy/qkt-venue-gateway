package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.Keepalive
import com.qkt.venuegateway.support.ReconnectingSocket
import com.qkt.venuegateway.support.SocketLink
import com.qkt.venuegateway.support.SocketProtocol
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

/**
 * One Bybit v5 socket at [url]. On every connect it first sends [auth]'s frame, when given, and waits for
 * Bybit to accept it, then subscribes [topics] (ten a request, as spot allows no more), and only then is the
 * link up ([onConnection] `true`). Pushes (frames with a `topic`) reach [onPush] in arrival order on the
 * reading thread. It pings every 20 s as Bybit asks, and a link silent for [silenceMs] is dropped, as is one
 * whose auth Bybit refuses (a refused subscription is logged; the rest of the link serves). The link itself
 * ([ReconnectingSocket]) reconnects after a backoff doubling from 1 s to 30 s.
 */
class BybitSocket(
    url: String,
    private val name: String,
    private val topics: () -> Collection<String>,
    private val onPush: (topic: String, frame: JsonObject) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val auth: (() -> String)? = null,
    silenceMs: Long = 60_000,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(BybitSocket::class.java)
    private val http = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var authed = CompletableFuture<Boolean>()

    @Volatile private var stopped = false
    private val link =
        ReconnectingSocket(url, "bybit-$name", Session(), Keepalive(PING_MS, """{"op":"ping"}""", silenceMs), http)

    /** Connects, and keeps the link alive from now on. */
    fun start() = link.start()

    /** Subscribes [added] now when the link is up; a link that comes up later subscribes [topics] anyway. */
    fun subscribe(added: Collection<String>) {
        if (!link.isUp) return
        added.chunked(TOPICS_PER_REQUEST).forEach { link.send(subscription(it)) }
    }

    override fun close() {
        stopped = true
        link.close()
        http.dispatcher.executorService.shutdown()
    }

    private fun onText(text: String) {
        val frame = json.parseToJsonElement(text).jsonObject
        val topic = frame.text("topic")
        if (topic != null) return onPush(topic, frame)
        val success = (frame["success"] as? JsonPrimitive)?.content
        when (frame.text("op")) {
            "auth" -> authed.complete(success == "true")
            "subscribe" ->
                if (success == "false") log.error("bybit {}: subscription refused: {}", name, frame.text("ret_msg"))
        }
    }

    private fun subscription(args: List<String>) =
        """{"op":"subscribe","args":${JsonArray(args.map(::JsonPrimitive))}}"""

    /** Bybit's side of the link: auth and its acceptance, then the topics, before up. */
    private inner class Session : SocketProtocol {
        override fun opening(link: SocketLink) {
            authed = CompletableFuture()
            auth?.let {
                link.send(it())
                val accepted = runCatching { authed.get(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                check(accepted) { "bybit $name: auth was not accepted" }
            }
            topics().chunked(TOPICS_PER_REQUEST).forEach { link.send(subscription(it)) }
        }

        override fun text(frame: String) = onText(frame)

        override fun up() = onConnection(true, "bybit $name open")

        override fun dropped(
            reason: String,
            wasUp: Boolean,
        ) {
            authed.complete(false)
            if (!stopped && wasUp) onConnection(false, reason)
        }
    }

    private companion object {
        const val PING_MS = 20_000L
        const val AUTH_TIMEOUT_MS = 10_000L
        const val TOPICS_PER_REQUEST = 10
    }
}

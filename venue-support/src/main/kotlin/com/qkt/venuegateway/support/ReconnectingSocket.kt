package com.qkt.venuegateway.support

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory

/** Where a venue's socket protocol plugs into [ReconnectingSocket]: everything here is the venue's, nothing else is. */
interface SocketProtocol {
    /**
     * Runs on every connect before the link counts as up, on the socket's own thread, so it may block on an answer
     * (an auth reply, a subscription ack) that [text] completes. [link] sends while opening. Throwing drops the link,
     * which then reconnects: nothing is sent on an unauthenticated link.
     */
    fun opening(link: SocketLink) {}

    /** One frame, in arrival order, on the reading thread. A failure is logged and the frame dropped. */
    fun text(frame: String)

    /** The link is up: [opening] succeeded. */
    fun up() {}

    /** The link dropped, [wasUp] when it had come up. Called for every drop, opening ones included. */
    fun dropped(
        reason: String,
        wasUp: Boolean,
    ) {}
}

/** Sends one text frame; false when the link refused it (closing, or gone). */
fun interface SocketLink {
    fun send(frame: String): Boolean
}

/**
 * Pings and silence: every [everyMs] the socket sends [frame] (null: none, the transport pings) and drops a link it
 * has heard nothing on for [silenceMs] (null: never), so a link the venue stopped answering reconnects.
 */
data class Keepalive(
    val everyMs: Long,
    val frame: String? = null,
    val silenceMs: Long? = null,
)

/**
 * One WebSocket to [url] that stays connected: a drop reconnects after a backoff doubling from 1 s to 30 s, reset
 * once a link comes up, and an event from a socket already replaced is ignored, so a late close never takes down
 * its successor. The venue's protocol is [protocol]'s; [name] labels its thread and logs. One thread per socket
 * runs opening, keepalive and reconnects.
 */
class ReconnectingSocket(
    private val url: String,
    private val name: String,
    private val protocol: SocketProtocol,
    private val keepalive: Keepalive? = null,
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(ReconnectingSocket::class.java)
    private val worker =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "socket-$name").apply { isDaemon = true } }
    private val lock = Any()
    private var socket: WebSocket? = null
    private var up = false
    private var backoffMs = INITIAL_BACKOFF_MS

    @Volatile private var heardMs = clock()

    @Volatile private var stopped = false

    /** Whether a link is up now. */
    val isUp: Boolean get() = synchronized(lock) { up }

    /** Connects, and keeps the link alive from now on. */
    fun start() {
        keepalive?.let { worker.scheduleWithFixedDelay(::keepAlive, it.everyMs, it.everyMs, TimeUnit.MILLISECONDS) }
        connect()
    }

    /** Sends [frame] on the link when it is up; false when no link is up or it refused the frame. */
    fun send(frame: String): Boolean = synchronized(lock) { socket?.takeIf { up } }?.send(frame) ?: false

    override fun close() {
        stopped = true
        synchronized(lock) { socket }?.cancel()
        worker.shutdownNow()
    }

    private fun connect() {
        http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun opened(webSocket: WebSocket) {
        synchronized(lock) { socket = webSocket }
        heardMs = clock()
        try {
            protocol.opening { webSocket.send(it) }
        } catch (e: Exception) {
            log.error("socket {} could not open: {}", name, e.message)
            webSocket.cancel()
            return
        }
        synchronized(lock) {
            if (socket !== webSocket) return
            up = true
            backoffMs = INITIAL_BACKOFF_MS
        }
        protocol.up()
    }

    private fun keepAlive() {
        val live = synchronized(lock) { socket } ?: return
        val silence = keepalive?.silenceMs
        if (silence != null && clock() - heardMs > silence) {
            log.warn("socket {}: nothing heard for {} ms, reconnecting", name, silence)
            live.cancel()
            return
        }
        keepalive?.frame?.let(live::send)
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
        protocol.dropped(reason, wasUp)
        if (stopped) return
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
            heardMs = clock()
            runCatching { protocol.text(text) }.onFailure { log.error("socket {} frame refused: {}", name, it.message) }
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

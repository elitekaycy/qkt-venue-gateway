package com.qkt.venuegateway.support

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ReconnectingSocketTest {
    private val server = MockWebServer()
    private val sockets = CopyOnWriteArrayList<ReconnectingSocket>()

    @AfterEach
    fun stop() {
        sockets.forEach(ReconnectingSocket::close)
        server.shutdown()
    }

    /** A venue end that answers each frame it receives with `echo:<frame>` and records what it got. */
    private fun venue(
        received: MutableList<String>,
        opened: CountDownLatch? = null,
    ) = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                opened?.countDown()
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                received += text
                webSocket.send("echo:$text")
            }
        },
    )

    private class Recorder(
        private val opening: (SocketLink) -> Unit = {},
    ) : SocketProtocol {
        val frames = CopyOnWriteArrayList<String>()
        val ups = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()

        override fun opening(link: SocketLink) = opening.invoke(link)

        override fun text(frame: String) {
            frames += frame
        }

        override fun up() {
            events += "up"
            ups.countDown()
        }

        override fun dropped(
            reason: String,
            wasUp: Boolean,
        ) {
            events += "dropped:$wasUp"
        }
    }

    private fun socket(
        protocol: SocketProtocol,
        keepalive: Keepalive? = null,
    ) = ReconnectingSocket(server.url("/ws").toString().replace("http", "ws"), "test", protocol, keepalive)
        .also(sockets::add)

    @Test
    fun `the opening handshake is sent before the link counts as up, and frames arrive in order`() {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(venue(received))
        val protocol = Recorder { it.send("auth") }
        val socket = socket(protocol).apply { start() }
        assertThat(protocol.ups.await(5, TimeUnit.SECONDS)).isTrue
        assertThat(socket.send("a")).isTrue
        assertThat(socket.send("b")).isTrue
        await { protocol.frames.size == 3 }
        assertThat(received).containsExactly("auth", "a", "b")
        assertThat(protocol.frames).containsExactly("echo:auth", "echo:a", "echo:b")
    }

    @Test
    fun `nothing is sent before the link is up, and a failed opening drops and reconnects`() {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(venue(received))
        server.enqueue(venue(received))
        var tries = 0
        val protocol = Recorder { if (++tries == 1) error("auth refused") else it.send("auth-$tries") }
        val socket = socket(protocol)
        assertThat(socket.send("early")).isFalse
        socket.start()
        assertThat(protocol.ups.await(10, TimeUnit.SECONDS)).isTrue
        assertThat(protocol.events).containsExactly("dropped:false", "up")
        await { received.isNotEmpty() }
        assertThat(received).containsExactly("auth-2")
    }

    @Test
    fun `a link the venue goes silent on is dropped and opened again`() {
        val received = CopyOnWriteArrayList<String>()
        val second = CountDownLatch(1)
        server.enqueue(venue(received))
        server.enqueue(venue(received, second))
        val protocol = Recorder()
        socket(protocol, Keepalive(everyMs = 100, frame = null, silenceMs = 300)).start()
        assertThat(second.await(10, TimeUnit.SECONDS)).isTrue
        assertThat(protocol.events.first()).isEqualTo("up")
        assertThat(protocol.events).contains("dropped:true")
    }

    @Test
    fun `a closed socket stops reconnecting`() {
        server.enqueue(venue(CopyOnWriteArrayList()))
        val protocol = Recorder()
        val socket = socket(protocol).apply { start() }
        assertThat(protocol.ups.await(5, TimeUnit.SECONDS)).isTrue
        socket.close()
        Thread.sleep(1_500)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(socket.isUp).isFalse
    }

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "condition not met in 5 s" }
            Thread.sleep(20)
        }
    }
}

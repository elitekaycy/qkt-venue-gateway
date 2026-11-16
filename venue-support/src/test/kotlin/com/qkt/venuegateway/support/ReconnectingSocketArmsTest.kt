package com.qkt.venuegateway.support

import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ReconnectingSocketArmsTest {
    private val server = MockWebServer().apply { start() }
    private val sockets = CopyOnWriteArrayList<ReconnectingSocket>()
    private val received = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() {
        sockets.forEach(ReconnectingSocket::close)
        server.close()
    }

    private fun venue(
        onOpen: (WebSocket) -> Unit = {},
        onText: (WebSocket, String) -> Unit = { _, _ -> },
    ) = server.enqueue(
        MockResponse
            .Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = onOpen(webSocket)

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        received += text
                        onText(webSocket, text)
                    }
                },
            ).build(),
    )

    private fun socket(
        protocol: SocketProtocol,
        keepalive: Keepalive? = null,
    ) = ReconnectingSocket(server.url("/ws").toString().replace("http", "ws"), "arms", protocol, keepalive)
        .also(sockets::add)

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "condition not met in 10 s" }
            Thread.sleep(20)
        }
    }

    private class TextOnly(
        private val refuse: String? = null,
    ) : SocketProtocol {
        val frames = CopyOnWriteArrayList<String>()

        override fun text(frame: String) {
            check(frame != refuse) { "refused $frame" }
            frames += frame
        }
    }

    @Test
    fun `a protocol that only reads frames is served, and reconnected after a drop, by the default hooks`() {
        venue(onOpen = {
            it.send("one")
            it.close(1000, "restart")
        })
        venue(onOpen = { it.send("two") })
        val protocol = TextOnly()
        socket(protocol).start()

        await { protocol.frames.contains("two") }

        assertThat(protocol.frames).containsExactly("one", "two")
    }

    @Test
    fun `a frame the protocol refuses is dropped and the link goes on delivering`() {
        venue(onOpen = {
            it.send("bad")
            it.send("good")
        })
        val protocol = TextOnly(refuse = "bad")
        socket(protocol).start()

        await { protocol.frames.isNotEmpty() }

        assertThat(protocol.frames).containsExactly("good")
    }

    @Test
    fun `a keepalive frame is sent every period, whether or not silence is watched`() {
        venue()
        venue()
        val plain = socket(TextOnly(), Keepalive(everyMs = 50, frame = "ping")).apply { start() }
        await { received.count { it == "ping" } >= 2 }
        plain.close()
        received.clear()

        val watched = socket(TextOnly(), Keepalive(everyMs = 50, frame = "pong", silenceMs = 60_000)).apply { start() }
        await { received.count { it == "pong" } >= 2 }

        assertThat(watched.isUp).isTrue
    }

    @Test
    fun `a keepalive with no frame and no silence limit sends nothing and keeps the link`() {
        venue()
        val socket = socket(TextOnly(), Keepalive(everyMs = 50)).apply { start() }
        await { socket.isUp }

        Thread.sleep(300)

        assertThat(received).isEmpty()
        assertThat(socket.isUp).isTrue
    }

    @Test
    fun `a frame sent while the opening handshake is still running is not sent, the link not being up yet`() {
        venue()
        var early: Boolean? = null
        val protocol =
            object : SocketProtocol {
                override fun opening(link: SocketLink) {
                    early = this@ReconnectingSocketArmsTest.sockets.single().send("too early")
                }

                override fun text(frame: String) = Unit
            }
        val link = socket(protocol).apply { start() }

        await { early != null && link.isUp }

        assertThat(early).isFalse
        assertThat(received).doesNotContain("too early")
    }
}

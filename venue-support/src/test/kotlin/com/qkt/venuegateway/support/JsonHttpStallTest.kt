package com.qkt.venuegateway.support

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A venue whose HTTP/2 socket stalls: it accepts connections, reads what is sent and never answers, not even a ping. */
class JsonHttpStallTest {
    private val server = ServerSocket(0)
    private val accepted = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                accepted += socket
                thread(isDaemon = true) { runCatching { socket.getInputStream().readAllBytes() } }
            }
        }
    }

    @AfterEach
    fun stop() {
        server.close()
        accepted.forEach { it.close() }
    }

    @Test
    fun `a stalled connection is dropped by its unanswered ping, so the next call opens a new one`() {
        val h2 = OkHttpClient.Builder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
        val http = JsonHttp("http://127.0.0.1:${server.localPort}", timeoutMs = 300, http = h2)
        assertThatThrownBy { http.get("/api/v2/public/get_instruments") }.isInstanceOf(IOException::class.java)
        Thread.sleep(800)
        assertThatThrownBy { http.get("/api/v2/public/get_instruments") }.isInstanceOf(IOException::class.java)
        assertThat(accepted).hasSize(2)
    }
}

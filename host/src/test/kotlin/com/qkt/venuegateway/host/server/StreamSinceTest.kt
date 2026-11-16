package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireSubmit
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StreamSinceTest {
    @Test
    fun `a stream asked from the start of an empty journal is not told to reset`(
        @TempDir dir: Path,
    ) {
        val venue = FakeAdapter()
        val gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1L },
                mapOf(
                    Role.TRADER to "t",
                ),
            ) {
                1L
            }
        gateway.start()
        val hub = QuoteHub(gateway)
        val server = GatewayServer(gateway, hub, "127.0.0.1", 0)
        val base = "ws://127.0.0.1:${server.start()}"
        val frames = LinkedBlockingQueue<String>()
        val opened = LinkedBlockingQueue<Boolean>()
        OkHttpClient().newWebSocket(
            Request
                .Builder()
                .url("$base/v1/stream?stream=${gateway.journal.stream}&since=0")
                .header("Authorization", "Bearer t")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: okhttp3.Response,
                ) {
                    opened += true
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    frames += text
                }
            },
        )

        assertThat(opened.poll(5, TimeUnit.SECONDS)).isTrue
        assertThat(gateway.journal.oldestSeq()).isNull()
        assertThat(frames.poll(400, TimeUnit.MILLISECONDS)).isNull()
        server.close()
        hub.close()
        gateway.close()
    }

    @Test
    fun `a stream asked from a position the journal still holds is not told to reset`(
        @TempDir dir: Path,
    ) {
        val venue = FakeAdapter()
        val gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1L },
                mapOf(
                    Role.TRADER to "t",
                ),
            ) {
                1L
            }
        gateway.start()
        venue.listener!!.connection(true, "test")
        gateway.desk.submit(WireSubmit("a-1", "BTC_USDC-PERPETUAL", "buy", "market", "0.1", null, null, "gtc", false))
        val server = GatewayServer(gateway, QuoteHub(gateway), "127.0.0.1", 0)
        val base = "ws://127.0.0.1:${server.start()}"
        val frames = LinkedBlockingQueue<String>()
        OkHttpClient().newWebSocket(
            Request
                .Builder()
                .url("$base/v1/stream?stream=${gateway.journal.stream}&since=${gateway.journal.latestSeq()}")
                .header("Authorization", "Bearer t")
                .build(),
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    frames += text
                }
            },
        )

        assertThat(gateway.journal.oldestSeq()).isNotNull
        assertThat(frames.poll(600, TimeUnit.MILLISECONDS)).isNull()
        server.close()
        gateway.close()
    }
}

package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireHealth
import com.qkt.vgp.WireMarks
import java.math.BigDecimal
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarkRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = FakeAdapter()
    private val http = OkHttpClient()
    private val minute = 60_000L
    private val now = 10_000 * minute + 30_000
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    private fun start() {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { now },
                mapOf(Role.TRADER to "t"),
            ) { now }
        gateway.start()
        venue.listener!!.connection(true, "test")
        hub = QuoteHub(gateway)
        server = GatewayServer(gateway, hub, "127.0.0.1", 0)
        base = "http://127.0.0.1:${server.start()}"
    }

    @AfterEach
    fun stop() {
        server.close()
        hub.close()
        gateway.close()
    }

    private fun get(path: String): Pair<Int, String> =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer t")
                    .build(),
            ).execute()
            .use { it.code to it.body!!.string() }

    private fun marks(path: String) = wireJson.decodeFromString(WireMarks.serializer(), get(path).second)

    private fun mark(
        time: Long,
        mark: String?,
        index: String? = "100",
    ) = VenueMark(time, mark?.let(::BigDecimal), index?.let(::BigDecimal))

    @Test
    fun `health names mark prices when the adapter declares them`() {
        venue.capabilities = setOf(Capability.BARS, Capability.MARK_PRICES)
        start()

        val health = wireJson.decodeFromString(WireHealth.serializer(), get("/v1/health").second)

        assertThat(health.capabilities).containsExactly("bars", "mark_prices")
    }

    @Test
    fun `marks are served one per window, the last the venue reported in it, oldest first`() {
        venue.marks += mark(2 * minute + 50_000, "101.5", "100.25")
        venue.marks += mark(2 * minute + 10_000, "101.0")
        venue.marks += mark(minute + 59_999, null, "99.9")
        start()

        val page = marks("/v1/marks?symbol=BTC-PERPETUAL&window_ms=$minute&from=$minute&to=${3 * minute}")

        assertThat(page.marks.map { it.time }).containsExactly(minute + 59_999, 2 * minute + 50_000)
        assertThat(page.marks.first().mark).isNull()
        assertThat(page.marks.first().index).isEqualTo("99.9")
        assertThat(page.marks.last().mark).isEqualTo("101.5")
        assertThat(page.marks.last().index).isEqualTo("100.25")
        assertThat(page.next).isNull()
    }

    @Test
    fun `a page covers a hundred windows and names the next one's start`() {
        start()

        val page = marks("/v1/marks?symbol=BTC-PERPETUAL&window_ms=$minute&from=0&to=$now")

        assertThat(page.next).isEqualTo(100 * minute)
        assertThat(venue.markCalls.single()).isEqualTo(0L to 100 * minute)
    }

    @Test
    fun `a window still open and reports outside the range asked are not served`() {
        venue.marks += mark(now - 1_000, "105")
        venue.marks += mark(9_998 * minute, "104")
        venue.marks += mark(9_997 * minute - 1, "103")
        start()

        val page = marks("/v1/marks?symbol=BTC-PERPETUAL&window_ms=$minute&from=${9_997 * minute}&to=$now")

        assertThat(page.marks.map { it.mark }).containsExactly("104")
        assertThat(page.next).isNull()
    }

    @Test
    fun `marks are refused as unsupported when the adapter does not declare them`() {
        venue.capabilities = setOf(Capability.BARS, Capability.QUOTES)
        start()

        val (code, body) = get("/v1/marks?symbol=BTC-PERPETUAL&window_ms=$minute&from=0&to=$now")

        assertThat(code).isEqualTo(501)
        assertThat(body).contains("\"code\":\"unsupported\"").contains("mark prices")
        assertThat(venue.markCalls).isEmpty()
    }

    @Test
    fun `a window that does not divide a day is a bad request naming it`() {
        start()

        val (code, body) = get("/v1/marks?symbol=BTC-PERPETUAL&window_ms=${7 * minute}&from=0&to=$now")

        assertThat(code).isEqualTo(400)
        assertThat(body).contains("window_ms must be whole minutes dividing a day: ${7 * minute}")
    }
}

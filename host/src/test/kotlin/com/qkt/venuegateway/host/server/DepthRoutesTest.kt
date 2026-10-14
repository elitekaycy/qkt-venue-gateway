package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireDepth
import com.qkt.vgp.WireHealth
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A [FakeAdapter] serving [depth], running [onDepth] as each read reaches it. */
private class DepthVenue : FakeAdapter() {
    val depth = CopyOnWriteArrayList<VenueDepth>()
    val depthCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
    var onDepth: () -> Unit = {}

    override fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = onDepth().let { depth.toList().also { depthCalls += fromMs to toMs } }
}

/** `/v1/depth` (wire spec §1, §3): declared in health, paged like open interest, refused when not declared. */
class DepthRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = DepthVenue()
    private val http = OkHttpClient()
    private val minute = 60_000L
    private val now = 50_000 * minute
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    private fun start(clock: () -> Long = { now }) {
        gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { now },
                mapOf(Role.TRADER to "t"),
                clock,
            )
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

    private fun page(path: String) = wireJson.decodeFromString(WireDepth.serializer(), get(path).second)

    private fun level(
        price: String,
        amount: String,
    ) = VenueLevel(BigDecimal(price), BigDecimal(amount))

    private fun book(timeMs: Long) = VenueDepth(timeMs, listOf(level("86490.6", "10")), listOf(level("86490.7", "10")))

    @Test
    fun `health names depth when the adapter declares it`() {
        venue.capabilities = setOf(Capability.DEPTH, Capability.QUOTES)
        start()

        val health = wireJson.decodeFromString(WireHealth.serializer(), get("/v1/health").second)

        assertThat(health.capabilities).containsExactly("quotes", "depth")
    }

    @Test
    fun `snapshots are served oldest first as price and amount pairs, with the next page's start`() {
        venue.depth += book(3 * minute)
        venue.depth +=
            VenueDepth(minute, listOf(level("8.62E4", "0.002"), level("86000", "0.5")), emptyList())
        start()

        val (code, body) = get("/v1/depth?symbol=BTC_USDC-PERPETUAL&from=$minute&to=$now")

        assertThat(code).isEqualTo(200)
        assertThat(body).contains(
            "\"depth\":[{\"time\":$minute,\"bids\":[[\"86200\",\"0.002\"],[\"86000\",\"0.5\"]],\"asks\":[]}",
        )
        val served = wireJson.decodeFromString(WireDepth.serializer(), body)
        assertThat(served.depth.map { it.time }).containsExactly(minute, 3 * minute)
        assertThat(served.depth.last().asks).containsExactly(listOf("86490.7", "10"))
        assertThat(served.next).isEqualTo(1_001 * minute)
        assertThat(venue.depthCalls.single()).isEqualTo(minute to 1_001 * minute - 1)
    }

    @Test
    fun `a side deeper than ten levels is cut to its best ten`() {
        val bids = (0 until 12).map { level((86_000 - it).toString(), "1") }
        venue.depth += VenueDepth(now - minute, bids, emptyList())
        start()

        val served = page("/v1/depth?symbol=BTC_USDC-PERPETUAL&from=${now - 2 * minute}&to=$now").depth.single()

        assertThat(served.bids.map { it[0] }).hasSize(10).startsWith("86000").endsWith("85991")
    }

    @Test
    fun `a page holds at most a thousand snapshots and the next starts after the last served`() {
        (0 until 1_001).forEach { venue.depth += book(minute + it * 1_000L) }
        start()

        val served = page("/v1/depth?symbol=BTC_USDC-PERPETUAL&from=$minute&to=$now")

        assertThat(served.depth).hasSize(1_000)
        assertThat(served.next).isEqualTo(minute + 999_000L + 1)
    }

    @Test
    fun `a snapshot the read itself recorded is served by that read, and later ones are not`() {
        var clock = now
        venue.depth += book(now + minute)
        venue.onDepth = {
            clock += 5
            venue.depth += book(clock)
        }
        start { clock }

        val served = page("/v1/depth?symbol=BTC_USDC-PERPETUAL&from=${now - minute}&to=${now + 2 * minute}")

        assertThat(served.depth.map { it.time }).containsExactly(now + 5)
        assertThat(served.next).isNull()
    }

    @Test
    fun `depth is refused as unsupported when the adapter does not declare it`() {
        venue.capabilities = setOf(Capability.BARS, Capability.QUOTES)
        start()

        val (code, body) = get("/v1/depth?symbol=BTC_USDC-PERPETUAL&from=0&to=$now")

        assertThat(code).isEqualTo(501)
        assertThat(body).contains("\"code\":\"unsupported\"").contains("depth")
        assertThat(venue.depthCalls).isEmpty()
    }

    @Test
    fun `a request without a symbol is refused naming it`() {
        start()

        val (code, body) = get("/v1/depth?from=0&to=$now")

        assertThat(code).isEqualTo(400)
        assertThat(body).contains("symbol missing")
    }
}

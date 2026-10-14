package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.vgp.WireHealth
import com.qkt.vgp.WireOpenInterest
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A [FakeAdapter] serving [openInterest], running [onOpenInterest] as each read reaches it. */
private class OpenInterestVenue : FakeAdapter() {
    val openInterest = CopyOnWriteArrayList<VenueOpenInterest>()
    val openInterestCalls = CopyOnWriteArrayList<Pair<Long, Long>>()
    var onOpenInterest: () -> Unit = {}

    override fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = onOpenInterest().let { openInterest.toList().also { openInterestCalls += fromMs to toMs } }
}

class OpenInterestRoutesTest {
    @TempDir lateinit var dir: Path
    private val venue = OpenInterestVenue()
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

    private fun page(path: String) = wireJson.decodeFromString(WireOpenInterest.serializer(), get(path).second)

    @Test
    fun `health names open_interest when the adapter declares it`() {
        venue.capabilities = setOf(Capability.OPEN_INTEREST, Capability.BARS)
        start()

        val health = wireJson.decodeFromString(WireHealth.serializer(), get("/v1/health").second)

        assertThat(health.capabilities).containsExactly("bars", "open_interest")
    }

    @Test
    fun `open interest is served a thousand minutes at a time, oldest first, with the next page's start`() {
        venue.openInterest += VenueOpenInterest(3 * minute, BigDecimal("1477.6341"))
        venue.openInterest += VenueOpenInterest(minute, BigDecimal("1470.0"))
        start()

        val (code, body) = get("/v1/open-interest?symbol=BTC_USDC-PERPETUAL&from=$minute&to=$now")

        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"open_interest\":[{\"time\":$minute,\"open_interest\":\"1470.0\"}")
        val served = wireJson.decodeFromString(WireOpenInterest.serializer(), body)
        assertThat(served.openInterest.map { it.time }).containsExactly(minute, 3 * minute)
        assertThat(served.openInterest.last().openInterest).isEqualTo("1477.6341")
        assertThat(served.next).isEqualTo(1_001 * minute)
        assertThat(venue.openInterestCalls.single()).isEqualTo(minute to 1_001 * minute - 1)
    }

    @Test
    fun `figures outside the asked window are dropped and the last page ends at now with no next`() {
        venue.openInterest += VenueOpenInterest(now - 2 * minute, BigDecimal("5"))
        venue.openInterest += VenueOpenInterest(now - minute, BigDecimal("6"))
        venue.openInterest += VenueOpenInterest(now + minute, BigDecimal("7"))
        start()

        val served = page("/v1/open-interest?symbol=BTC_USDC-PERPETUAL&from=${now - minute}&to=${now + hour()}")

        assertThat(served.openInterest.map { it.openInterest }).containsExactly("6")
        assertThat(served.next).isNull()
        assertThat(venue.openInterestCalls.single()).isEqualTo(now - minute to now + hour())
    }

    @Test
    fun `a figure the read itself made known is served by that read, not the next`() {
        var clock = now
        venue.onOpenInterest = {
            clock += 5
            venue.openInterest += VenueOpenInterest(clock, BigDecimal("1477.6341"))
        }
        start { clock }

        val served = page("/v1/open-interest?symbol=BTC_USDC-PERPETUAL&from=${now - minute}&to=${now + minute}")

        assertThat(served.openInterest.map { it.time }).containsExactly(now + 5)
        assertThat(served.next).isNull()
    }

    @Test
    fun `open interest is refused as unsupported when the adapter does not declare it`() {
        venue.capabilities = setOf(Capability.BARS, Capability.QUOTES)
        start()

        val (code, body) = get("/v1/open-interest?symbol=BTC_USDC-PERPETUAL&from=0&to=$now")

        assertThat(code).isEqualTo(501)
        assertThat(body).contains("\"code\":\"unsupported\"").contains("open interest")
        assertThat(venue.openInterestCalls).isEmpty()
    }

    @Test
    fun `a request without a symbol is refused naming it`() {
        start()

        val (code, body) = get("/v1/open-interest?from=0&to=$now")

        assertThat(code).isEqualTo(400)
        assertThat(body).contains("symbol missing")
    }

    private fun hour() = 60 * minute
}

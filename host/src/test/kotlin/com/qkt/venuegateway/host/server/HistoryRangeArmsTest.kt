package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import java.math.BigDecimal
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** History routes over a venue that answers more than the range asked, as a sloppy venue may. */
class HistoryRangeArmsTest {
    @TempDir lateinit var dir: Path

    private class SloppyVenue : FakeAdapter() {
        @Volatile var books: List<VenueDepth> = emptyList()

        @Volatile var figures: List<VenueOpenInterest> = emptyList()

        override fun depth(
            code: String,
            fromMs: Long,
            toMs: Long,
        ) = books

        override fun openInterest(
            code: String,
            fromMs: Long,
            toMs: Long,
        ) = figures
    }

    private val venue = SloppyVenue()
    private val http = OkHttpClient()
    private val now = 270_000L
    private val symbol = "symbol=BTC_USDC-PERPETUAL"
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub
    private lateinit var server: GatewayServer
    private var base = ""

    @BeforeEach
    fun start() {
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

    private fun get(path: String): String =
        http
            .newCall(
                Request
                    .Builder()
                    .url(base + path)
                    .header("Authorization", "Bearer t")
                    .build(),
            ).execute()
            .use { it.body.string() }

    private fun book(time: Long) =
        VenueDepth(
            time,
            listOf(VenueLevel(BigDecimal("1"), BigDecimal("1"))),
            listOf(VenueLevel(BigDecimal("2"), BigDecimal("1"))),
        )

    @Test
    fun `marks outside the range, or of a window that has not closed, are not served`() {
        venue.marks += VenueMark(-5, BigDecimal("1"), null)
        venue.marks += VenueMark(30_000, BigDecimal("2"), null)
        venue.marks += VenueMark(250_000, BigDecimal("3"), null)
        venue.marks += VenueMark(280_000, BigDecimal("4"), null)

        val page = get("/v1/marks?$symbol&window_ms=60000&from=0&to=600000")

        assertThat(page).contains("\"time\":30000").doesNotContain("\"time\":-5", "\"time\":250000", "\"time\":280000")
    }

    @Test
    fun `depth snapshots before or after the range are not served`() {
        venue.books = listOf(book(5), book(100), book(250_000))

        val page = get("/v1/depth?$symbol&from=10&to=200000")

        assertThat(page).contains("\"time\":100").doesNotContain("\"time\":5,", "\"time\":250000")
    }

    @Test
    fun `open interest past a page of a thousand names where the next page starts`() {
        venue.figures = (0L..1_000L).map { VenueOpenInterest(it, BigDecimal.TEN) }

        val page = get("/v1/open-interest?$symbol&from=0&to=200000")

        assertThat(page).contains("\"next\":1000").doesNotContain("\"time\":1000,")
    }

    @Test
    fun `funding rates published before the range are not served`() {
        venue.rates += VenueFundingRate(5, BigDecimal("0.1"), null)
        venue.rates += VenueFundingRate(20, BigDecimal("0.2"), null)

        val page = get("/v1/funding-rates?$symbol&from=10&to=200000")

        assertThat(page).contains("0.2").doesNotContain("0.1")
    }
}

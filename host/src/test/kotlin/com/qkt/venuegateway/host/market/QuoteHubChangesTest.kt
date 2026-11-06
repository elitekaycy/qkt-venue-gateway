package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuoteHubChangesTest {
    @TempDir lateinit var dir: Path
    private var now = 1_000L
    private val venue = ListedVenue()
    private lateinit var gateway: Gateway
    private lateinit var hub: QuoteHub

    private fun start(heartbeatMs: Long = 600_000) {
        val shelf = InstrumentShelf.open(dir.resolve("i.db")) { now }
        gateway = Gateway(venue, Journal.open(dir.resolve("j.db")), shelf, emptyMap()) { now }
        gateway.start()
        venue.fake.listener!!.connection(true, "test")
        hub = QuoteHub(gateway, refreshMs = 20, batchMs = 50, heartbeatMs = heartbeatMs).also { it.start() }
    }

    @AfterEach
    fun stop() {
        hub.close()
        gateway.close()
    }

    private fun quote(
        code: String,
        bid: String,
    ) = VenueQuote(code, BigDecimal(bid), BigDecimal("0.6"), timeMs = now)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition never held" }
            Thread.sleep(5)
        }
    }

    private fun event(
        code: String,
        series: String,
        event: String,
    ) = perpetual(code).copy(
        kind = InstrumentKind.EVENT,
        expiryMs = 9_000,
        event = EventTerms(series, event, StrikeType.NONE, null, null, 0, 9_000),
    )

    @Test
    fun `a series or event root subscribes every listed contract of it, and nothing else`() {
        venue.listed += listOf(event("KXA-E1-T1", "KXA", "KXA-E1"), event("KXA-E2-T1", "KXA", "KXA-E2"))
        venue.listed += event("KXB-E1-T1", "KXB", "KXB-E1")
        start()
        val got = CopyOnWriteArrayList<VenueQuote>()

        hub.subscribe(emptySet(), setOf("KXA")) { got += it }
        assertThat(venue.fake.subscriptions.last()).containsExactlyInAnyOrder("KXA-E1-T1", "KXA-E2-T1")
        hub.subscribe(emptySet(), setOf("KXB-E1")) {}
        venue.fake.listener!!.quote(quote("KXB-E1-T1", "0.4"))

        assertThat(venue.fake.subscriptions.last()).containsExactlyInAnyOrder("KXA-E1-T1", "KXA-E2-T1", "KXB-E1-T1")
        assertThat(got).isEmpty()
    }

    @Test
    fun `a changes client gets changed quotes in batches, never a refresh, until its heartbeat`() {
        start()
        val code = "BTC_USDC-PERPETUAL"
        venue.fake.listener!!.quote(quote(code, "1"))
        val batches = CopyOnWriteArrayList<List<VenueQuote>>()
        val plain = CopyOnWriteArrayList<VenueQuote>()
        hub.subscribe(setOf(code), emptySet()) { plain += it }

        hub.subscribeChanges(setOf(code), emptySet()) { batches += it }
        await { batches.size == 1 }
        venue.fake.listener!!.quote(quote(code, "2"))
        venue.fake.listener!!.quote(quote(code, "3"))
        await { batches.last().any { it.bid == BigDecimal("3") } }
        now += 1
        await { plain.any { it.timeMs == now } }

        assertThat(batches.first().map { it.bid }).containsExactly(BigDecimal("1"))
        assertThat(batches.flatten()).noneMatch { it.timeMs == now }
    }

    @Test
    fun `a changes client's heartbeat sends every quote it holds with the time advanced, while the feed holds`() {
        start(heartbeatMs = 50)
        val batches = CopyOnWriteArrayList<List<VenueQuote>>()
        hub.subscribe(setOf("BTC_USDC-PERPETUAL"), emptySet()) {}
        hub.subscribeChanges(setOf("BTC_USDC-PERPETUAL"), emptySet()) { batches += it }
        venue.fake.listener!!.quote(quote("BTC_USDC-PERPETUAL", "1"))
        now += 1
        await { batches.any { b -> b.any { it.timeMs == now } } }

        venue.fake.listener!!.quoteFeed(false, "ticker socket dropped")
        Thread.sleep(100)
        now += 1
        Thread.sleep(200)

        assertThat(batches.flatten()).noneMatch { it.timeMs == now }
    }
}

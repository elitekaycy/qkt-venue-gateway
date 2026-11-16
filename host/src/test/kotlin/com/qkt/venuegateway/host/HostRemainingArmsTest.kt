package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.venuegateway.host.orders.DeskResult
import com.qkt.venuegateway.host.orders.OrderDesk
import com.qkt.venuegateway.host.wire.WireFundingMapping
import com.qkt.vgp.WireSubmit
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostRemainingArmsTest {
    @TempDir lateinit var dir: Path
    private val reads = AtomicInteger()
    private val listings = AtomicInteger()

    private val venue =
        object : FakeAdapter() {
            override fun openOrders(): List<VenueOrder> = super.openOrders().also { reads.incrementAndGet() }

            override fun instruments() = super.instruments().also { listings.incrementAndGet() }
        }

    @Volatile private var now = 10_000_000L

    private fun gateway() =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { now },
            emptyMap(),
        ) {
            now
        }.also { it.start() }

    private fun until(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the venue coming back reconciles at once, and the venue dropping does not`() {
        val gateway = gateway()
        val reconciler = Reconciler(gateway, periodMs = 3_600_000).also { it.start() }
        until { reads.get() >= 1 }
        val first = reads.get()

        venue.listener!!.connection(false, "dropped")
        Thread.sleep(200)
        val afterDrop = reads.get()
        venue.listener!!.connection(true, "back")
        until { reads.get() > afterDrop }

        assertThat(afterDrop).describedAs("a drop reconciles nothing").isEqualTo(first)
        reconciler.close()
        gateway.close()
    }

    @Test
    fun `on a journal that survived a restart a new submit is placed without looking for a lost one`() {
        val file = dir.resolve("kept.db")
        Journal.open(file).close()
        val journal = Journal.open(file)
        val desk = OrderDesk(journal, venue, { true }) { now }

        val placed =
            desk.submit(
                WireSubmit("n-1", "BTC_USDC-PERPETUAL", "buy", "market", "0.1", null, null, "gtc", false),
            )

        assertThat(journal.createdThisRun).isFalse
        assertThat((placed as DeskResult.Ok).created).isTrue
        assertThat(venue.placed).containsExactly("n-1")
    }

    @Test
    fun `funding goes on the wire with the position it was charged on, or none`() {
        val held = VenueFunding("tx-1", "BTC_USDC-PERPETUAL", BigDecimal("0.50"), "USDC", BigDecimal("2"), 7)

        assertThat(WireFundingMapping.funding(held).position).isEqualTo("2")
        assertThat(WireFundingMapping.funding(held.copy(position = null)).position).isNull()
        assertThat(WireFundingMapping.funding(held).amount).isEqualTo("0.50")
    }

    @Test
    fun `a listing older than the recheck is read again for the next root subscriber`() {
        val gateway = gateway()
        val hub = QuoteHub(gateway, listingRecheckMs = 1_000).also { it.start() }
        hub.subscribe(emptySet(), setOf("BTC_USDC")) {}
        val before = listings.get()

        now += 5_000
        hub.subscribe(emptySet(), setOf("BTC_USDC")) {}

        assertThat(listings.get() - before).isEqualTo(1)
        hub.close()
        gateway.close()
    }
}

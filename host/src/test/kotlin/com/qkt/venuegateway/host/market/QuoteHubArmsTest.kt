package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuoteHubArmsTest {
    @TempDir lateinit var dir: Path
    private val fake = FakeAdapter()
    private val listings = AtomicInteger()

    @Volatile private var failing = false

    private val venue: VenueAdapter =
        object : VenueAdapter by fake {
            override fun instruments(): List<Instrument> {
                listings.incrementAndGet()
                if (failing) throw VenueUnavailableException("venue down")
                return fake.instruments()
            }
        }

    private fun gateway(): Gateway =
        Gateway(
            venue,
            Journal.open(dir.resolve("j.db")),
            InstrumentShelf.open(dir.resolve("i.db")) { 1_000L },
            emptyMap(),
        ) {
            1_000L
        }.also { it.start() }

    @Test
    fun `a hub with only coded subscribers never reads the listing for roots`() {
        val gateway = gateway()
        val hub = QuoteHub(gateway, rootRefreshMs = 20).also { it.start() }
        hub.subscribe(setOf("BTC_USDC-PERPETUAL"), emptySet()) {}
        val read = listings.get()

        Thread.sleep(300)

        assertThat(listings.get()).describedAs("no root refresh reads the listing").isEqualTo(read)
        hub.close()
        gateway.close()
    }

    @Test
    fun `a listing read within the recheck is not read again for the next root subscriber`() {
        val gateway = gateway()
        val hub = QuoteHub(gateway, listingRecheckMs = 60_000).also { it.start() }
        val before = listings.get()

        hub.subscribe(emptySet(), setOf("BTC_USDC")) {}
        hub.subscribe(emptySet(), setOf("BTC_USDC")) {}

        assertThat(listings.get() - before).isEqualTo(1)
        hub.close()
        gateway.close()
    }

    @Test
    fun `a root refresh that cannot read the listing leaves the subscription serving what it had`() {
        val gateway = gateway()
        val hub = QuoteHub(gateway, listingRecheckMs = 0, rootRefreshMs = 20).also { it.start() }
        val got = CopyOnWriteArrayList<VenueQuote>()
        hub.subscribe(emptySet(), setOf("BTC_USDC")) { got += it }
        failing = true
        val failedAt = listings.get()
        val deadline = System.currentTimeMillis() + 5_000
        while (listings.get() < failedAt + 2) {
            check(System.currentTimeMillis() < deadline) { "the refresh never tried" }
            Thread.sleep(10)
        }

        fake.listener!!.quote(VenueQuote("BTC_USDC-9OCT26-82000-P", BigDecimal("1"), BigDecimal("2"), timeMs = 1))

        assertThat(got.map { it.symbol }).contains("BTC_USDC-9OCT26-82000-P")
        hub.close()
        gateway.close()
    }
}

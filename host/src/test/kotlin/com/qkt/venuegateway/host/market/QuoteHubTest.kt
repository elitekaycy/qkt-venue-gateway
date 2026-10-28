package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.server.Role
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuoteHubTest {
    @Test
    fun `a root subscriber gets its options' quotes and not other instruments'`(
        @TempDir dir: Path,
    ) {
        val venue = FakeAdapter()
        val gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1_000L },
                mapOf(
                    Role.TRADER to "t",
                ),
            ) { 1_000L }
        gateway.start()
        val hub = QuoteHub(gateway).also { it.start() }
        val got = CopyOnWriteArrayList<VenueQuote>()
        hub.subscribe(emptySet(), setOf("BTC_USDC")) { got += it }

        venue.listener!!.quote(VenueQuote("BTC_USDC-9OCT26-82000-P", BigDecimal("640"), BigDecimal("655"), timeMs = 1))
        venue.listener!!.quote(VenueQuote("BTC_USDC-PERPETUAL", BigDecimal("1"), BigDecimal("2"), timeMs = 1))

        assertThat(got.map { it.symbol }).containsExactly("BTC_USDC-9OCT26-82000-P")
        assertThat(venue.subscriptions.last()).isEqualTo(setOf("BTC_USDC-9OCT26-82000-P"))
        hub.close()
        gateway.close()
    }

    @Test
    fun `a root subscription takes up options the venue lists after it, at the next root refresh`(
        @TempDir dir: Path,
    ) {
        val listed = CopyOnWriteArrayList(FakeAdapter().instruments())
        val fake = FakeAdapter()
        val venue =
            object : VenueAdapter by fake {
                override fun instruments() = listed.toList()
            }
        val gateway =
            Gateway(
                venue,
                Journal.open(dir.resolve("j.db")),
                InstrumentShelf.open(dir.resolve("i.db")) { 1L },
                emptyMap(),
            ) {
                1L
            }
        gateway.start()
        val hub = QuoteHub(gateway, listingRecheckMs = 0, rootRefreshMs = 20).also { it.start() }
        hub.subscribe(emptySet(), setOf("BTC_USDC")) {}
        val later = listed.first { it.underlying == "BTC_USDC" }.copy(code = "BTC_USDC-10OCT26-82000-P")
        listed += later

        val deadline = System.currentTimeMillis() + 5_000
        while (fake.subscriptions.none { later.code in it }) {
            check(System.currentTimeMillis() < deadline) { "never subscribed the new expiry: ${fake.subscriptions}" }
            Thread.sleep(10)
        }
        assertThat(fake.subscriptions.first()).doesNotContain(later.code).contains("BTC_USDC-9OCT26-82000-P")
        hub.close()
        gateway.close()
    }
}

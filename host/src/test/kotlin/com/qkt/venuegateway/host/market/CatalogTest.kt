package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueUnavailableException
import java.nio.file.Path
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogTest {
    @TempDir lateinit var dir: Path
    private var now = 1_000L

    private fun catalog(venue: ListedVenue): Catalog {
        val shelf = InstrumentShelf.open(dir.resolve("i.db")) { now }
        return Catalog(venue, shelf, clock = { now })
    }

    @Test
    fun `requests are served from the last read, so the venue is not asked per request`() {
        val venue = ListedVenue()
        val catalog = catalog(venue)

        repeat(3) { catalog.current() }
        venue.listed += perpetual("ETH_USDC-PERPETUAL")

        assertThat(venue.reads.get()).isEqualTo(1)
        assertThat(catalog.current().listing.map { it.code }).doesNotContain("ETH_USDC-PERPETUAL")
        assertThat(catalog.refresh().byCode).containsKey("ETH_USDC-PERPETUAL")
    }

    @Test
    fun `a code missing from the last read is read again, at most once per ten seconds`() {
        val venue = ListedVenue()
        val catalog = catalog(venue)
        catalog.current()
        venue.listed += perpetual("ETH_USDC-PERPETUAL")

        assertThat(catalog.find("ETH_USDC-PERPETUAL")).isNull()
        now += 10_000
        assertThat(catalog.find("ETH_USDC-PERPETUAL")?.code).isEqualTo("ETH_USDC-PERPETUAL")
        assertThat(catalog.find("SOL_USDC-PERPETUAL")).isNull()
        now += 10_000
        assertThat(catalog.find("SOL_USDC-PERPETUAL")).isNull()
        assertThat(venue.reads.get()).isEqualTo(3)
    }

    @Test
    fun `a code the venue no longer lists is answered as the adapter looks it up`() {
        val venue = ListedVenue().apply { unlisted += perpetual("OLD_USDC-PERPETUAL") }

        assertThat(catalog(venue).find("OLD_USDC-PERPETUAL")?.kind).isEqualTo("perpetual")
    }

    @Test
    fun `the first request fails when the venue cannot list, and a failed timed read keeps the last one served`() {
        val venue = ListedVenue().apply { down = true }
        val catalog = catalog(venue)
        assertThatThrownBy { catalog.current() }.isInstanceOf(VenueUnavailableException::class.java)
        venue.down = false
        val first = catalog.current()
        venue.down = true
        val timer = Executors.newSingleThreadScheduledExecutor()

        catalog.start(timer)
        Thread.sleep(200)
        timer.shutdownNow()

        assertThat(catalog.current()).isSameAs(first)
    }

    @Test
    fun `the listing is read again on the host's timer every refresh period the adapter asks for`() {
        val venue = ListedVenue(listingRefreshMs = 10)
        val catalog = catalog(venue)
        val timer = Executors.newSingleThreadScheduledExecutor()

        catalog.start(timer)
        val deadline = System.currentTimeMillis() + 5_000
        while (venue.reads.get() < 3) {
            check(System.currentTimeMillis() < deadline) { "read ${venue.reads.get()} times" }
            Thread.sleep(5)
        }
        timer.shutdownNow()

        assertThat(catalog.current().byCode).containsKey("BTC_USDC-PERPETUAL")
    }
}

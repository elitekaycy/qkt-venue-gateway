package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.FakeAdapter
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A venue whose listing a test changes, counting reads; it answers [unlisted] codes by [instrument]. */
internal class ListedVenue(
    private val fake: FakeAdapter = FakeAdapter(),
    override val listingRefreshMs: Long = 60_000,
) : VenueAdapter by fake {
    val listed = CopyOnWriteArrayList(fake.instruments())
    val unlisted = CopyOnWriteArrayList<Instrument>()
    val reads = AtomicInteger()

    @Volatile var down = false

    override fun instruments(): List<Instrument> {
        if (down) throw VenueUnavailableException("listing down")
        reads.incrementAndGet()
        return listed.toList()
    }

    override fun instrument(code: String) = unlisted.firstOrNull { it.code == code }
}

/** A plain perpetual named [code]. */
internal fun perpetual(code: String) =
    Instrument(
        code,
        InstrumentKind.PERPETUAL,
        "USDC",
        BigDecimal.ONE,
        BigDecimal("0.5"),
        BigDecimal("0.001"),
        BigDecimal("0.001"),
    )

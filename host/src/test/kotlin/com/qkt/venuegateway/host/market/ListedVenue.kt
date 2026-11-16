package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.EventTerms
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentFilter
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.host.FakeAdapter
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A venue whose listing a test changes, counting reads; it answers [unlisted] codes by [instrument]. */
internal class ListedVenue(
    val fake: FakeAdapter = FakeAdapter(),
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

    /** What the venue holds beyond its listing; null (the default) is a venue that cannot search. */
    @Volatile var searchable: List<Instrument>? = null
    val searches = CopyOnWriteArrayList<InstrumentFilter>()

    override fun search(filter: InstrumentFilter): List<Instrument>? {
        searches += filter
        return searchable
    }
}

/** An event contract [code] of [series] and [event], expiring at 9000. */
internal fun eventContract(
    code: String,
    series: String,
    event: String,
) = Instrument(
    code,
    InstrumentKind.EVENT,
    "USD",
    BigDecimal.ONE,
    BigDecimal("0.01"),
    BigDecimal("0.01"),
    BigDecimal.ONE,
    expiryMs = 9_000,
    event = EventTerms(series, event, StrikeType.NONE, null, null, 0, 9_000),
)

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

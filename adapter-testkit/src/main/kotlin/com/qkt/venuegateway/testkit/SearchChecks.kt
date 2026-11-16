package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentFilter
import com.qkt.venuegateway.adapter.VenueAdapter
import org.assertj.core.api.Assertions.assertThat

/** What an adapter that can search ([VenueAdapter.search]) keeps: a series is found well formed, with its own. */
internal object SearchChecks {
    /** The series of [active], when it is an event contract and the adapter searches, holds it and lists well. */
    fun series(
        adapter: VenueAdapter,
        active: Instrument,
    ) {
        val series = active.event?.series ?: return
        val found = adapter.search(InstrumentFilter(series = series)) ?: return
        ContractChecks.listing(found)
        assertThat(found.map { it.event?.series }.toSet()).describedAs("series found for $series").containsOnly(series)
        assertThat(found.map { it.code }).describedAs("search of series $series").contains(active.code)
    }
}

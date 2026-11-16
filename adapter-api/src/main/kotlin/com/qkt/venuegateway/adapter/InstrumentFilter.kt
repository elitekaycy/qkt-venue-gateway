package com.qkt.venuegateway.adapter

/**
 * What a listing search narrows by ([VenueAdapter.search]): an event contract's [series] (its family) or one [event]
 * of it. One of them is always set; both set means the event.
 */
data class InstrumentFilter(
    val series: String? = null,
    val event: String? = null,
) {
    init {
        require(series != null || event != null) { "an instrument filter names a series or an event" }
    }
}

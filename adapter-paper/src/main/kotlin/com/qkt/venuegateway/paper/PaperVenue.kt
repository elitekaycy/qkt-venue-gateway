package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueErrors
import com.qkt.venuegateway.deribit.client.DeribitException

/** Deribit's public API as paper reads it: its errors sorted as the Deribit adapter sorts them ([VenueErrors]). */
internal object PaperVenue : VenueErrors("deribit", {
    (it as? DeribitException)?.code
}, { DeribitException.unavailable(it) })

/** Runs [call] against Deribit's public API, turning Deribit's failures into the gateway's. */
internal inline fun <T> venue(call: () -> T): T = PaperVenue.venue(call)

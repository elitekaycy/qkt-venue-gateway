package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueUnavailableException
import java.io.IOException

/** Runs [call] against Deribit's public API; Deribit unreachable is the venue unavailable. */
internal fun <T> venue(call: () -> T): T =
    try {
        call()
    } catch (e: IOException) {
        throw VenueUnavailableException("deribit: ${e.message}", e)
    }

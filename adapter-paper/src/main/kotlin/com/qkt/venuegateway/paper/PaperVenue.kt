package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.client.DeribitException
import java.io.IOException

/**
 * Runs [call] against Deribit's public API: Deribit unreachable, or answering an error it documents as not serving
 * ([DeribitException.unavailable]), is the venue unavailable; any other Deribit error is a refusal, as the request
 * did nothing.
 */
internal fun <T> venue(call: () -> T): T =
    try {
        call()
    } catch (e: DeribitException) {
        throw if (e.unavailable) {
            VenueUnavailableException(e.message ?: "deribit ${e.code}", e)
        } else {
            VenueRefusedException(
                e.message ?: "deribit ${e.code}",
            )
        }
    } catch (e: IOException) {
        throw VenueUnavailableException("deribit: ${e.message}", e)
    }

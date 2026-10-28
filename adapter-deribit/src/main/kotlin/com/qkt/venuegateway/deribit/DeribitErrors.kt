package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.client.DeribitException
import java.io.IOException

/**
 * Deribit's failures as the gateway's two: a refusal ([VenueRefusedException]) means Deribit answered
 * and the request did nothing; unavailable ([VenueUnavailableException]) means the outcome is unknown
 * or Deribit is not serving ([DeribitException.unavailable]), so the host looks the order up by its label instead
 * of assuming it failed.
 */
internal object DeribitErrors {
    fun translate(failure: Exception): RuntimeException =
        when (failure) {
            is DeribitException ->
                if (failure.unavailable) {
                    VenueUnavailableException(failure.message ?: "deribit ${failure.code}", failure)
                } else {
                    VenueRefusedException(failure.message ?: "deribit ${failure.code}")
                }
            is IOException -> VenueUnavailableException("deribit: ${failure.message}", failure)
            is RuntimeException -> failure
            else -> IllegalStateException(failure)
        }

    /** Runs [call], turning Deribit's failures into the gateway's. */
    inline fun <T> venue(call: () -> T): T =
        try {
            call()
        } catch (e: DeribitException) {
            throw translate(e)
        } catch (e: IOException) {
            throw translate(e)
        }
}

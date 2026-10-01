package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.client.DeribitException
import java.io.IOException

/**
 * Deribit's failures as the gateway's two: a refusal ([VenueRefusedException]) means Deribit answered
 * and the request did nothing; unavailable ([VenueUnavailableException]) means the outcome is unknown
 * or Deribit is not serving, so the host looks the order up by its label instead of assuming it failed.
 * The unavailable codes are the ones Deribit documents as throttling, retry, maintenance, internal
 * failure, timeout or an expired session (docs.deribit.com/articles/errors, read 2026-10-01); every
 * other code is an answer.
 */
internal object DeribitErrors {
    private val UNAVAILABLE =
        setOf(
            10000, // authorization_required
            10001, // error: a general failure, outcome unknown
            10028, // too_many_requests
            10040, // retry
            10041, // settlement_in_progress
            10047, // matching_engine_queue_full
            10066, // too_many_concurrent_requests
            11051, // system_maintenance
            11094, // internal_server_error
            13009, // unauthorized: an expired or wrong token
            13025, // method_switched_off_by_admin
            13028, // temporarily_unavailable
            13503, // unavailable
            13888, // timed_out
        )

    fun translate(failure: Exception): RuntimeException =
        when (failure) {
            is DeribitException ->
                if (failure.code in UNAVAILABLE) {
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

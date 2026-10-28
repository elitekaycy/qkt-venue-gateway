package com.qkt.venuegateway.adapter

import java.io.IOException

/**
 * A venue's failures sorted into the gateway's two, written once for every adapter. A refusal
 * ([VenueRefusedException]) means the venue answered and the request did nothing; unavailable
 * ([VenueUnavailableException]) means the outcome is unknown or the venue is not serving, so the host looks an order
 * up by its label instead of assuming it failed. [codeOf] reads the venue's own error code from its exception (null
 * for anything that is not the venue's error); [unavailable] says which codes mean not serving, from the venue's
 * documentation. Unreachable ([IOException]) is unavailable; any other failure is passed on as it is. [venue] names
 * the venue in messages.
 */
open class VenueErrors(
    private val venue: String,
    private val codeOf: (Throwable) -> Int?,
    private val unavailable: (Int) -> Boolean,
) {
    fun translate(failure: Exception): RuntimeException {
        val code = codeOf(failure)
        return when {
            code != null && unavailable(code) -> VenueUnavailableException(failure.message ?: "$venue $code", failure)
            code != null -> VenueRefusedException(failure.message ?: "$venue $code")
            failure is IOException -> VenueUnavailableException("$venue: ${failure.message}", failure)
            failure is RuntimeException -> failure
            else -> IllegalStateException(failure)
        }
    }

    /** Runs [call], turning the venue's failures (its errors, and it being unreachable) into the gateway's. */
    inline fun <T> venue(call: () -> T): T =
        try {
            call()
        } catch (e: Exception) {
            throw if (isVenueFailure(e)) translate(e) else e
        }

    /** Whether [failure] is the venue's: its own error, or it being unreachable. */
    fun isVenueFailure(failure: Exception): Boolean = codeOf(failure) != null || failure is IOException
}

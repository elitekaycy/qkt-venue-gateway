package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.bybit.client.BybitException
import java.io.IOException

/**
 * Bybit's failures as the gateway's two: a refusal ([VenueRefusedException]) means Bybit answered and the
 * request did nothing; unavailable ([VenueUnavailableException]) means the outcome is unknown or Bybit is not
 * serving, so the host looks the order up by its label instead of assuming it failed. The unavailable codes
 * are the HTTP statuses Bybit sends without an answer (403 and 429 rate limits, 5xx) and the `retCode`s it
 * documents as a timeout, a rate limit, a restart, an internal error, an order still processing, or a
 * timestamp outside the receive window (bybit-exchange.github.io/docs/v5/error, read 2026-10-05); every other
 * code is an answer. Bybit answers a malformed request with 10001 (`params error`, `Qty invalid`): a refusal.
 */
internal object BybitErrors {
    private val UNAVAILABLE =
        setOf(
            403, // IP rate limit (HTTP)
            429, // system frequency protection (HTTP)
            10000, // server timeout
            10002, // request time outside the receive window
            10006, // too many visits
            10016, // server error, service restarting
            10018, // IP rate limit
            10019, // trade service restarting
            10429, // system frequency protection
            110079, // order is processing
            170007, // spot: backend timeout
            170032, // spot: network error
            170234, // spot: system error
        )

    fun translate(failure: Exception): RuntimeException =
        when (failure) {
            is BybitException ->
                if (failure.code in UNAVAILABLE || failure.code in 500..599) {
                    VenueUnavailableException(failure.message ?: "bybit ${failure.code}", failure)
                } else {
                    VenueRefusedException(failure.message ?: "bybit ${failure.code}")
                }
            is IOException -> VenueUnavailableException("bybit: ${failure.message}", failure)
            is RuntimeException -> failure
            else -> IllegalStateException(failure)
        }

    /** Runs [call], turning Bybit's failures into the gateway's. */
    inline fun <T> venue(call: () -> T): T =
        try {
            call()
        } catch (e: BybitException) {
            throw translate(e)
        } catch (e: IOException) {
            throw translate(e)
        }
}

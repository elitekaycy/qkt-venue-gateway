package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueErrors
import com.qkt.venuegateway.bybit.client.BybitException

/**
 * Bybit's failures as the gateway's two: a refusal ([VenueRefusedException]) means Bybit answered and the
 * request did nothing; unavailable ([VenueUnavailableException]) means the outcome is unknown or Bybit is not
 * serving, so the host looks the order up by its label instead of assuming it failed. The unavailable codes
 * are the HTTP statuses Bybit sends without an answer (403 and 429 rate limits, 5xx) and the `retCode`s it
 * documents as a timeout, a rate limit, a restart, an internal error, an order still processing, or a
 * timestamp outside the receive window (bybit-exchange.github.io/docs/v5/error, read 2026-10-05); every other
 * code is an answer. Bybit answers a malformed request with 10001 (`params error`, `Qty invalid`): a refusal.
 */
internal object BybitErrors : VenueErrors(
    "bybit",
    { (it as? BybitException)?.code },
    { it in UNAVAILABLE || it in 500..599 },
)

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

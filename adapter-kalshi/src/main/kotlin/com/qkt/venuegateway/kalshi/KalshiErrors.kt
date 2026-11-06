package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.VenueErrors
import com.qkt.venuegateway.kalshi.client.KalshiException

/**
 * Kalshi's failures as the gateway's two, by HTTP status: 429 (a rate limit) and 5xx mean Kalshi could not answer
 * now, so the outcome is unknown and the host looks an order up by its label ([VenueUnavailableException]); every
 * other status is Kalshi's answer, a refusal carrying its own code and message (`400 invalid_order: reduce_only can
 * only be used with IoC orders`, `400 insufficient_balance`, `400 invalid_price`, `409 order_already_exists`,
 * recorded on the demo environment). Kalshi being unreachable is unavailable too.
 */
internal object KalshiErrors : VenueErrors(
    "kalshi",
    { (it as? KalshiException)?.status },
    { it == TOO_MANY_REQUESTS || it in 500..599 },
)

private const val TOO_MANY_REQUESTS = 429

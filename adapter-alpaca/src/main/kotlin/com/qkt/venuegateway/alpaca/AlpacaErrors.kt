package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.VenueErrors
import com.qkt.venuegateway.alpaca.client.AlpacaHttpException
import com.qkt.venuegateway.alpaca.client.AlpacaJson
import com.qkt.venuegateway.support.JsonReads.text

/**
 * Alpaca's failures sorted by the shared classifier. Alpaca answers a request it will not do with 4xx and its own
 * `code` and `message` (403 `40310000` for buying power or balance, 422 `42210000` for a bad symbol, price or time
 * in force, 422 `40010001` for a malformed or duplicate order): a refusal, with Alpaca's message as the reason. 401
 * (the key refused), 429 (rate limit) and 5xx mean Alpaca did not serve the request: unavailable, so the host
 * resolves the order by its label instead of assuming it failed.
 */
internal object AlpacaErrors : VenueErrors(
    "alpaca",
    { (it as? AlpacaHttpException)?.status },
    { it == UNAUTHORIZED || it == RATE_LIMITED || it >= SERVER_ERROR },
) {
    /** Alpaca's own `code` in [failure]'s body, when it sent one: a duplicate label is told from a malformed order by it. */
    fun code(failure: AlpacaHttpException): String? =
        runCatching { AlpacaJson.obj(failure.body, "error").text("code") }.getOrNull()
}

private const val UNAUTHORIZED = 401
private const val RATE_LIMITED = 429
private const val SERVER_ERROR = 500

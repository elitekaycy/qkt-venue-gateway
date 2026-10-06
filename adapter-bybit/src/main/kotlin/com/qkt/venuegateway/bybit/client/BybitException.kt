package com.qkt.venuegateway.bybit.client

/**
 * Bybit answered a request without doing it: a non-zero `retCode` with its `retMsg`, or an HTTP status it
 * sends without an answer (403 and 429 rate limits, 5xx), whose status is then the [code].
 */
class BybitException(
    val code: Int,
    message: String,
) : RuntimeException("bybit $code: $message")

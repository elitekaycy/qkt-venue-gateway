package com.qkt.venuegateway.support

/**
 * Retries a read until it answers: [block] up to [attempts] times, [delayMs] apart, until it returns non-null.
 * For reads only (a venue that has not yet indexed an order it just accepted); never for anything that changes the
 * venue, where a retry is a second order.
 */
object Retry {
    fun <T : Any> until(
        attempts: Int,
        delayMs: Long,
        sleep: (Long) -> Unit = Thread::sleep,
        block: () -> T?,
    ): T? {
        require(attempts > 0) { "attempts must be positive: $attempts" }
        repeat(attempts) { i ->
            block()?.let { return it }
            if (i < attempts - 1) sleep(delayMs)
        }
        return null
    }
}

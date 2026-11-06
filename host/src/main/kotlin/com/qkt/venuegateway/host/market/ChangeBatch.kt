package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueQuote

/**
 * One `refresh=changes` client's outgoing quotes (wire spec §4a): a quote that differs from the one last sent to it
 * (its time aside) waits for the next [flush], which sends every waiting quote as one batch; [heartbeat] sends the
 * quotes it holds with their time advanced, so a quiet book still reads as live. Thread-safe.
 */
internal class ChangeBatch(
    private val send: (List<VenueQuote>) -> Unit,
) {
    private val sent = HashMap<String, VenueQuote>()
    private val waiting = LinkedHashMap<String, VenueQuote>()

    /** Queues [quote] when it changed since the last one sent of its code; a change undone drops it. */
    @Synchronized
    fun offer(quote: VenueQuote) {
        if (sent[quote.symbol]?.copy(timeMs = quote.timeMs) == quote) {
            waiting.remove(quote.symbol)
        } else {
            waiting[quote.symbol] = quote
        }
    }

    /** Sends every waiting quote as one batch, when any waits. */
    @Synchronized
    fun flush() {
        if (waiting.isEmpty()) return
        deliver(waiting.values.toList())
        waiting.clear()
    }

    /** Sends [held] as one batch, the quotes waiting with it superseded. */
    @Synchronized
    fun heartbeat(held: List<VenueQuote>) {
        waiting.clear()
        if (held.isNotEmpty()) deliver(held)
    }

    private fun deliver(batch: List<VenueQuote>) {
        batch.forEach { sent[it.symbol] = it }
        send(batch)
    }
}

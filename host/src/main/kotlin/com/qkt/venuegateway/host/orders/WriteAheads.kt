package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.wire.WireMapping
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireSubmit
import java.util.concurrent.ConcurrentHashMap

/**
 * Submits written ahead to the [journal] whose venue answer has not been journaled yet. Each place
 * attempt is timed ([attempted]); one made in the last [graceMs] may still reach the venue (its call is in
 * flight, or the venue has not shown it yet), so it is never written off or rejected meanwhile ([settled]).
 * [resolve] looks for what became of one through [recovery] and journals it: the venue's order and the
 * fills found, or, when the venue holds no trace of a settled one, a rejection.
 */
internal class WriteAheads(
    private val journal: Journal,
    private val recovery: OrderRecovery,
    private val clock: () -> Long,
    private val graceMs: Long = GRACE_MS,
) {
    private val attempts = ConcurrentHashMap<String, Long>()

    /** A place of [clientOrderId] is about to be sent. */
    fun attempted(clientOrderId: String) {
        attempts[clientOrderId] = clock()
    }

    /** Whether [clientOrderId]'s last place was sent more than [graceMs] ago (or not by this process). */
    fun settled(clientOrderId: String): Boolean = attempts[clientOrderId]?.let { clock() - it >= graceMs } ?: true

    /** The order [body] became at the venue, journaled with its fills; null when the venue holds no trace. */
    fun found(body: WireSubmit): WireOrder? {
        val found = recovery.find(body) ?: return null
        found.fills.forEach { journal.appendFill(WireMapping.fill(it)) }
        return WireMapping.order(found.order).also { journal.appendOrder(it) }
    }

    /** Resolves [body] once settled: what [found] finds, else a journaled rejection. Null while it is not settled. */
    fun resolve(body: WireSubmit): WireOrder? {
        if (!settled(body.clientOrderId)) return null
        return found(body)
            ?: WireMapping.rejected(body, "the venue holds no trace of it", clock()).also { journal.appendOrder(it) }
    }

    private companion object {
        /** Longer than any venue call's timeout, and than a venue takes to show an order it accepted. */
        const val GRACE_MS = 120_000L
    }
}

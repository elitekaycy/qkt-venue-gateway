package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.host.wire.WireMapping
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Brings the journal back to the venue's truth (design §6): every open order as the venue reports it;
 * a journaled working order the venue no longer lists, resolved by [OrderRecovery] or, with no trace
 * left, closed as cancelled (it is not working: the venue would list it); a write-ahead record the venue's
 * answer never reached, resolved by [OrderRecovery] (by label, else from its fills) or closed as
 * rejected when the venue holds no trace of it; every position that changed ([PositionWatch]), read
 * before the history (its failure is logged and the history still read); and every fill, settlement and
 * funding record (the last two when the adapter declares them) since the last run that read them in full
 * (less [overlapMs]; the last [lookbackMs] on a new journal), which the journal journals once. Reading from
 * the last full run, not from the newest record, keeps a fill missed in an outage from being skipped when
 * a later one arrives by push first. Runs on [start], each time the venue link comes back, and every
 * [periodMs]; one failed run is logged and the next tries again.
 */
class Reconciler(
    private val gateway: Gateway,
    private val periodMs: Long = 60_000,
    private val overlapMs: Long = 5 * 60_000,
    private val lookbackMs: Long = 7 * 24 * 3_600_000L,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Reconciler::class.java)
    private val timer =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gateway-reconciler").apply { isDaemon = true } }

    /** Reconciles now, then every [periodMs] and on every venue reconnect. */
    fun start() {
        gateway.onConnection = { up -> if (up) timer.execute(::quietly) }
        timer.scheduleWithFixedDelay(::quietly, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    /** One reconciliation. Throws when the venue cannot answer. */
    @Synchronized
    fun reconcile() {
        val venue = gateway.adapter
        val journal = gateway.journal
        val listener = gateway.listener
        val open = venue.openOrders()
        open.forEach(listener::order)
        val listed = open.map { it.clientOrderId }.toSet()
        journal.workingOrders().filter { it.clientOrderId !in listed }.forEach { gone ->
            val found = gateway.desk.recovery.find(WireMapping.submitOf(gone))
            if (found != null) {
                found.fills.forEach(listener::fill)
                listener.order(found.order)
            } else {
                journal.appendOrder(gone.copy(status = "cancelled", updatedAt = gateway.clock()))
            }
        }
        journal.unresolved().forEach { gateway.desk.resolve(it) }
        // A position the venue cannot report must not hold back the history below.
        runCatching {
            gateway.positions.refresh(
                null,
            )
        }.onFailure { log.warn("positions not refreshed: {}", it.message) }
        val now = gateway.clock()
        val from = (journal.meta(RECONCILED_THROUGH)?.toLong() ?: (now - lookbackMs)) - overlapMs
        venue.fills(from, now).forEach(listener::fill)
        if (Capability.SETTLEMENTS in venue.capabilities) venue.settlements(from, now).forEach(listener::settlement)
        if (Capability.FUNDING in venue.capabilities) venue.funding(from, now).forEach(listener::funding)
        journal.transaction { journal.setMeta(RECONCILED_THROUGH, now.toString()) }
    }

    override fun close() {
        timer.shutdownNow()
    }

    private companion object {
        /** Up to when the venue's history was last read in full: the next read starts there, less the overlap. */
        const val RECONCILED_THROUGH = "reconciled_through"
    }

    private fun quietly() {
        runCatching { reconcile() }.onFailure {
            log.warn(
                "reconciliation failed, retrying on the next run: {}",
                it.message,
            )
        }
    }
}

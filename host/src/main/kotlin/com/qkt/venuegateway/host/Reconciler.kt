package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.host.journal.latestFundingTime
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
 * before the history so it is told even when the history cannot be read; and every fill, settlement and
 * funding record (the last two when the adapter declares them) since the newest journaled one (less
 * [overlapMs]; [lookbackMs] on a new journal), which the journal
 * journals once. Runs on [start], each time the venue link comes back, and every
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
        gateway.positions.refresh(null)
        val now = gateway.clock()
        venue.fills(since(journal.latestFillTime(), now), now).forEach(listener::fill)
        if (Capability.SETTLEMENTS in venue.capabilities) {
            venue.settlements(since(journal.latestSettlementTime(), now), now).forEach(listener::settlement)
        }
        if (Capability.FUNDING in venue.capabilities) {
            venue.funding(since(journal.latestFundingTime(), now), now).forEach(listener::funding)
        }
    }

    override fun close() {
        timer.shutdownNow()
    }

    private fun since(
        newest: Long,
        now: Long,
    ): Long = (if (newest > 0) newest else now - lookbackMs) - overlapMs

    private fun quietly() {
        runCatching { reconcile() }.onFailure {
            log.warn(
                "reconciliation failed, retrying on the next run: {}",
                it.message,
            )
        }
    }
}

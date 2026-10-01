package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WirePosition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory

/**
 * Keeps the client's view of the account's positions current (wire spec §4 `position`): after a new fill
 * or settlement of a symbol ([changed]) the venue's positions are read again, on this watch's own thread
 * (never the venue's: an adapter may not call its venue from its push thread), and each position that
 * changed is journaled, quantity `"0"` once flat. The reconciler [refresh]es every position on each run,
 * so a read that failed is made good. Positions are keyed by symbol and, on a hedging account, ticket.
 */
internal class PositionWatch(
    private val adapter: VenueAdapter,
    private val journal: Journal,
    private val clock: () -> Long,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(PositionWatch::class.java)
    private val executor =
        Executors.newSingleThreadExecutor {
            Thread(
                it,
                "gateway-positions",
            ).apply { isDaemon = true }
        }
    private val told = ConcurrentHashMap<Pair<String, String?>, WirePosition>()

    /** [symbol]'s position may have changed: read it again soon. */
    fun changed(symbol: String) {
        executor.execute {
            runCatching {
                refresh(
                    setOf(symbol),
                )
            }.onFailure { log.warn("positions of {} not read: {}", symbol, it.message) }
        }
    }

    /** Reads the venue's positions and journals each of [symbols] (null: every one) that changed since last told. */
    @Synchronized
    fun refresh(symbols: Set<String>?) {
        val now = WireReads.positions(adapter.positions()).positions.associateBy { it.symbol to it.ticket }
        val keys = (now.keys + told.keys).filter { symbols == null || it.first in symbols }
        for (key in keys) {
            val position = now[key] ?: WirePosition(key.first, "0", "0", key.second)
            if (told[key] == position) continue
            journal.appendPosition(position, clock())
            if (position.quantity == "0") told.remove(key) else told[key] = position
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

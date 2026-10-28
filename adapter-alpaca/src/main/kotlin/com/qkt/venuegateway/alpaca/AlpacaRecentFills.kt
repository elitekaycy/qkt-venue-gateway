package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.VenueFill
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Executions the trade stream pushed in the last [keepMs]. Alpaca's FILL activities trail the stream (measured on
 * paper: an activity listed 0.6 s after its order read filled, so a reconcile right after a fill missed it), and they
 * are its only list of executions, so a fill read back by time is the activities merged with these, one per execution
 * id. After a restart this is empty, and by then the activities have caught up.
 */
class AlpacaRecentFills(
    private val clock: () -> Long,
    private val keepMs: Long = 600_000,
) {
    private val fills = ConcurrentLinkedDeque<Pair<Long, VenueFill>>()

    fun add(fill: VenueFill) {
        val now = clock()
        fills.addLast(now to fill)
        while (fills.peekFirst()?.let { now - it.first > keepMs } == true) fills.pollFirst()
    }

    /**
     * [listed] with every recent fill from [fromMs] to [toMs], oldest first, one per execution id. The stream's copy
     * wins over the listed one: it carries what was received net of a coin fee ([AlpacaCoinFees]), which the
     * activities do not.
     */
    fun merged(
        listed: List<VenueFill>,
        fromMs: Long,
        toMs: Long,
    ): List<VenueFill> {
        val streamed = fills.map { it.second }.filter { it.timeMs in fromMs..toMs }.associateBy { it.fillId }
        return (listed.filter { it.fillId !in streamed } + streamed.values).sortedBy { it.timeMs }
    }
}

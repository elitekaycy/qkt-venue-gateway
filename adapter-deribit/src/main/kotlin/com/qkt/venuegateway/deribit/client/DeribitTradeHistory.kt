package com.qkt.venuegateway.deribit.client

/**
 * Walks Deribit's trade history, which pages by time: each page starts at the last trade's timestamp
 * (so trades sharing that millisecond are not skipped) and repeats are dropped by trade id. A page
 * that cannot advance (a full page of trades in one millisecond) fails rather than loop or lose trades.
 */
internal object DeribitTradeHistory {
    fun collect(
        fromMs: Long,
        toMs: Long,
        page: (startMs: Long) -> DeribitPage<DeribitTrade>,
    ): List<DeribitTrade> {
        val seen = LinkedHashMap<String, DeribitTrade>()
        var start = fromMs
        while (true) {
            val next = page(start)
            next.items.forEach { seen.putIfAbsent(it.tradeId, it) }
            if (!next.more || next.items.isEmpty()) break
            val last = next.items.last().timestampMs
            check(last > start) { "deribit trade history does not advance past $start ms; it cannot be paged further" }
            start = last
            if (start > toMs) break
        }
        return seen.values.toList()
    }
}

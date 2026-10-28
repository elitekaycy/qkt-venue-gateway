package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueRefusedException
import java.math.BigDecimal

/**
 * Closed bars of any window (wire spec §3 bars), built once for every venue from the klines its adapter serves:
 * a window the venue serves is its klines; any other is built from the largest resolution it serves that divides
 * the window (first open, highest high, lowest low, last close, summed volume). Only windows starting in
 * `[fromMs, toMs)` and closed by `nowMs` are served, so the kline still forming never is; the adapter is asked
 * for the klines of those windows alone, and asked even when there are none, so it still refuses a code it does
 * not serve.
 */
internal object BarBuilder {
    /** [code]'s bars of [windowMs] starting in `[fromMs, toMs)` and closed by [nowMs], oldest first. */
    fun closed(
        adapter: VenueAdapter,
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<VenueBar> {
        val base =
            adapter.klineResolutions.filter { windowMs % it == 0L }.maxOrNull()
                ?: throw VenueRefusedException("${adapter.id} serves no kline dividing a $windowMs ms window")
        val first = Math.floorDiv(fromMs + windowMs - 1, windowMs) * windowMs
        val last = Math.floorDiv(minOf(toMs - 1, nowMs - windowMs), windowMs) * windowMs
        val klines = adapter.klines(code, base, first, if (last < first) first else last + windowMs)
        return klines
            .groupBy { Math.floorDiv(it.startMs, windowMs) * windowMs }
            .filterKeys { it in first..last }
            .toSortedMap()
            .map { (start, parts) -> bar(start, parts.sortedBy { it.startMs }) }
    }

    private fun bar(
        start: Long,
        parts: List<VenueBar>,
    ) = VenueBar(
        start,
        parts.first().open,
        parts.maxOf { it.high },
        parts.minOf { it.low },
        parts.last().close,
        parts.fold(BigDecimal.ZERO) { sum, k -> sum + k.volume },
    )
}

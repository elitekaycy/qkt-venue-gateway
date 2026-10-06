package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BybitKline
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import java.math.BigDecimal

/**
 * Closed bars and per-window marks from Bybit's klines. Bybit serves klines of 1, 3, 5, 15, 30, 60, 120, 240,
 * 360 and 720 minutes and a day, aligned to UTC; a window it serves is read as it is, any other whole-minute
 * window that divides a day is built from the longest Bybit interval dividing it. The kline holding now is
 * still forming and is never served.
 */
internal class BybitBars(
    private val market: BybitPublicClient,
    private val category: String,
) {
    /** [code]'s bars of [windowMs] starting in `[fromMs, toMs)` and closed by [nowMs], oldest first. */
    fun closed(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<VenueBar> =
        windows(code, "kline", windowMs, fromMs, toMs, nowMs).map { (start, parts) ->
            VenueBar(
                start,
                parts.first().open,
                parts.maxOf { it.high },
                parts.minOf { it.low },
                parts.last().close,
                parts.fold(BigDecimal.ZERO) { sum, k -> sum + (k.volume ?: BigDecimal.ZERO) },
            )
        }

    /**
     * [code]'s mark and index per window of [windowMs] starting in `[fromMs, toMs)` and closed by [nowMs]: each
     * window's closing mark and index (the close of Bybit's mark and index klines), at the window's last
     * millisecond, the latest moment the venue could have reported them, so a value is never served early.
     */
    fun marks(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<VenueMark> {
        val mark = windows(code, "mark-price-kline", windowMs, fromMs, toMs, nowMs).toMap()
        val index = windows(code, "index-price-kline", windowMs, fromMs, toMs, nowMs).toMap()
        return (mark.keys + index.keys).sorted().map { start ->
            VenueMark(start + windowMs - 1, mark[start]?.last()?.close, index[start]?.last()?.close)
        }
    }

    private fun windows(
        code: String,
        kind: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<Pair<Long, List<BybitKline>>> {
        if (windowMs <= 0 || windowMs % MINUTE_MS != 0L || DAY_MS % windowMs != 0L) {
            throw VenueRefusedException("bar window $windowMs ms is not a whole number of minutes dividing a day")
        }
        val (interval, intervalMs) = INTERVALS.last { windowMs % it.second == 0L }
        val first = Math.floorDiv(fromMs + windowMs - 1, windowMs) * windowMs
        val last = minOf(toMs - 1, nowMs - windowMs)
        if (first > last) return emptyList()
        val klines = market.klines(category, code, kind, interval, intervalMs, first, last + windowMs - 1)
        return klines
            .groupBy { Math.floorDiv(it.startMs, windowMs) * windowMs }
            .filterKeys { it in first..last }
            .toSortedMap()
            .map { (start, parts) -> start to parts.sortedBy { it.startMs } }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
        val INTERVALS =
            listOf(1L, 3, 5, 15, 30, 60, 120, 240, 360, 720).map { "$it" to it * MINUTE_MS } + ("D" to DAY_MS)
    }
}

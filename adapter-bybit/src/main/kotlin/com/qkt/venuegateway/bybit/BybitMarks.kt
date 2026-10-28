package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BYBIT_KLINE_INTERVALS
import com.qkt.venuegateway.bybit.client.BybitKline
import com.qkt.venuegateway.bybit.client.BybitPublicClient

/**
 * Per-window marks from Bybit's mark-price and index-price klines: a window Bybit serves is read as it is, any
 * other whole-minute window that divides a day from the longest Bybit interval dividing it. The kline holding now
 * is still forming and is never read.
 */
internal class BybitMarks(
    private val market: BybitPublicClient,
    private val category: String,
) {
    /**
     * [code]'s mark and index per window of [windowMs] starting in `[fromMs, toMs)` and closed by [nowMs]: each
     * window's closing mark and index (the close of Bybit's mark and index klines), at the window's last
     * millisecond, the latest moment the venue could have reported them, so a value is never served early.
     */
    fun sampled(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<VenueMark> {
        val mark = windows(code, "mark-price-kline", windowMs, fromMs, toMs, nowMs)
        val index = windows(code, "index-price-kline", windowMs, fromMs, toMs, nowMs)
        return (mark.keys + index.keys).sorted().map { start ->
            VenueMark(start + windowMs - 1, mark[start]?.close, index[start]?.close)
        }
    }

    /** The last kline of each closed window, by the window's start. */
    private fun windows(
        code: String,
        kind: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): Map<Long, BybitKline> {
        if (windowMs <= 0 || windowMs % MINUTE_MS != 0L || DAY_MS % windowMs != 0L) {
            throw VenueRefusedException("bar window $windowMs ms is not a whole number of minutes dividing a day")
        }
        val intervalMs = BYBIT_KLINE_INTERVALS.keys.filter { windowMs % it == 0L }.max()
        val first = Math.floorDiv(fromMs + windowMs - 1, windowMs) * windowMs
        val last = minOf(toMs - 1, nowMs - windowMs)
        if (first > last) return emptyMap()
        val klines =
            market.klines(
                category,
                code,
                kind,
                BYBIT_KLINE_INTERVALS.getValue(intervalMs),
                intervalMs,
                first,
                last + windowMs - 1,
            )
        return klines
            .groupBy { Math.floorDiv(it.startMs, windowMs) * windowMs }
            .filterKeys { it in first..last }
            .mapValues { (_, parts) -> parts.maxBy { it.startMs } }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}

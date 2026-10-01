package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.deribit.client.DERIBIT_KLINE_MINUTES
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import java.math.BigDecimal

/**
 * Closed bars of any window that divides a day (wire spec §3), from Deribit's klines: a window Deribit
 * serves is its klines; any other is built from the largest resolution Deribit serves that divides it
 * (a 4-hour bar from 2-hour klines: first open, last close, highest high, lowest low, summed volume).
 * Only bars starting in `[fromMs, toMs)` and closed by `nowMs` are returned: Deribit also returns the
 * kline holding `fromMs` and a last kline still forming.
 */
object DeribitBars {
    private const val MINUTE_MS = 60_000L

    fun closed(
        market: DeribitMarketData,
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
        nowMs: Long,
    ): List<VenueBar> {
        val windowMinutes = windowMs / MINUTE_MS
        val base = DERIBIT_KLINE_MINUTES.filter { windowMinutes % it == 0L }.max()
        val klines = market.klines(code, base, fromMs, toMs)
        return klines
            .groupBy { it.startMs / windowMs * windowMs }
            .filterKeys { it in fromMs until toMs && it + windowMs <= nowMs }
            .map { (start, parts) -> bar(start, parts) }
            .sortedBy { it.startMs }
    }

    private fun bar(
        start: Long,
        parts: List<DeribitKline>,
    ): VenueBar {
        val ordered = parts.sortedBy { it.startMs }
        return VenueBar(
            start,
            ordered.first().open,
            ordered.maxOf { it.high },
            ordered.minOf { it.low },
            ordered.last().close,
            ordered.fold(BigDecimal.ZERO) { sum, k -> sum + k.volume },
        )
    }
}

package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import java.time.LocalDate

/** The kline lengths Deribit serves, in minutes (`1D` is 1440). */
val DERIBIT_KLINE_MINUTES: List<Long> = listOf(1, 3, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1_440)

/** Deribit's public market data, as adapters use it; [DeribitPublicClient] is the real one. */
interface DeribitMarketData {
    fun instruments(
        currency: String,
        kind: String,
    ): List<DeribitInstrument>

    /** One instrument by [name], expired ones included (Deribit keeps archived contracts answerable). */
    fun instrument(name: String): DeribitInstrument

    fun ticker(name: String): DeribitTicker

    fun klines(
        name: String,
        minutes: Long,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitKline>

    fun deliveryPrices(
        index: String,
        count: Int,
    ): List<Pair<LocalDate, BigDecimal>>
}

/** A live ticker feed, as adapters use it; [DeribitTickerStream] is the real one. */
interface DeribitTickers : AutoCloseable {
    fun start()

    fun subscribe(names: Set<String>)
}

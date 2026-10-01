package com.qkt.venued.deribit

import java.math.BigDecimal
import java.time.LocalDate

/** Deribit's public market data, as adapters use it; [DeribitPublicClient] is the real one. */
interface DeribitMarketData {
    fun instruments(
        currency: String,
        kind: String,
    ): List<DeribitInstrument>

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

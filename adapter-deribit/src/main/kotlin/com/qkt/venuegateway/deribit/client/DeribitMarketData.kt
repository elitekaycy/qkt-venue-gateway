package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import java.time.LocalDate

/** The kline lengths Deribit serves, in minutes (`1D` is 1440). */
val DERIBIT_KLINE_MINUTES: List<Long> = listOf(1, 3, 5, 10, 15, 30, 60, 120, 180, 360, 720, 1_440)

/** Deribit's name of the kline [minutes] long (`1D` for a day); a length it does not serve is refused. */
internal fun klineResolution(minutes: Long): String =
    when (minutes) {
        !in DERIBIT_KLINE_MINUTES -> throw IllegalArgumentException("Deribit has no $minutes-minute klines")
        1_440L -> "1D"
        else -> minutes.toString()
    }

/** The most trades Deribit answers in one call (`count` above it is refused as `value is too high`). */
const val TRADES_PER_CALL: Int = 1_000

/** Deribit's public market data, as adapters use it; [DeribitPublicClient] is the real one. */
interface DeribitMarketData {
    fun instruments(
        currency: String,
        kind: String,
    ): List<DeribitInstrument>

    /** One instrument by [name], expired ones included (Deribit keeps archived contracts answerable). */
    fun instrument(name: String): DeribitInstrument

    fun ticker(name: String): DeribitTicker

    /** [name]'s order book as it stands, at most [depth] levels a side. */
    fun orderBook(
        name: String,
        depth: Int,
    ): DeribitOrderBook = error("this market data does not read order books")

    fun klines(
        name: String,
        minutes: Long,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitKline>

    /** The hourly funding of perpetual [name] whose hours end from [fromMs] to [toMs], oldest first. */
    fun fundingRates(
        name: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitFundingRate> = error("this market data does not read funding rates")

    /** The first trade of [name] from [fromMs] to [toMs] (both inclusive), or with [newest] the last; null when none. */
    fun edgeTrade(
        name: String,
        fromMs: Long,
        toMs: Long,
        newest: Boolean,
    ): DeribitMarkTrade? = error("this market data does not read trades")

    /**
     * The first [count] trades of [name] from [fromMs] to [toMs] (both inclusive) in time order (trades of one
     * millisecond in any order), and whether more follow.
     */
    fun tradesFrom(
        name: String,
        fromMs: Long,
        toMs: Long,
        count: Int,
    ): DeribitPage<DeribitMarkTrade> = error("this market data does not read trades")

    /**
     * The first [count] prints of [name]'s trade tape from [fromMs] to [toMs] (both inclusive) in time order (prints
     * of one millisecond in any order), and whether more follow.
     */
    fun tape(
        name: String,
        fromMs: Long,
        toMs: Long,
        count: Int,
    ): DeribitPage<DeribitPublicTrade> = error("this market data does not read the trade tape")

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

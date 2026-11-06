package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.Page
import com.qkt.venuegateway.support.Paging
import kotlinx.serialization.json.JsonObject

/**
 * Kalshi's market data over [rest]: the series, the open events with their markets, one market by ticker, a
 * market's book, its public trades and its candlesticks. Every list is paged by Kalshi's `cursor` (`""` on the last
 * page). Times Kalshi takes in a query are Unix seconds.
 */
class KalshiPublic(
    private val rest: KalshiRest,
) {
    /** Every series Kalshi lists, in one answer (about 16,000; Kalshi does not page it). */
    fun series(): List<KalshiSeries> = rest.get("/series").objects("series").map(KalshiMarketJson::series)

    /** One series, or null when Kalshi has none by [ticker]. */
    fun series(ticker: String): KalshiSeries? =
        rest.orNull { rest.get("/series/$ticker") }?.let { KalshiMarketJson.series(it["series"] as JsonObject) }

    /** Every open event with its markets, [EVENTS_PER_PAGE] a page. */
    fun openEvents(): List<KalshiEvent> {
        val params = listOf("status" to "open", "with_nested_markets" to "true", "limit" to "$EVENTS_PER_PAGE")
        return Paging.cursor(
            fetch = { cursor ->
                val page = rest.get("/events", params + listOfNotNull(cursor?.let { "cursor" to it }))
                Page(page.objects("events").map(KalshiMarketJson::event), page.text("cursor"))
            },
            id = KalshiEvent::eventTicker,
            time = { 0L },
        )
    }

    /** The market [ticker], settled or not, or null when Kalshi has none by it. */
    fun market(ticker: String): KalshiMarket? =
        rest.orNull { rest.get("/markets/$ticker") }?.let { KalshiMarketJson.market(it["market"] as JsonObject) }

    /** The markets [tickers] as they stand now, [TICKERS_PER_CALL] a call; a ticker Kalshi lacks is left out. */
    fun markets(tickers: Collection<String>): List<KalshiMarket> =
        tickers.chunked(TICKERS_PER_CALL).flatMap { chunk ->
            val page = rest.get("/markets", listOf("tickers" to chunk.joinToString(",")))
            page.objects("markets").map(KalshiMarketJson::market)
        }

    /** [ticker]'s book, at most [depth] levels a side. */
    fun book(
        ticker: String,
        depth: Int,
    ): KalshiBook = KalshiMarketJson.book(rest.get("/markets/$ticker/orderbook", listOf("depth" to "$depth")))

    /** [ticker]'s public trades from [fromMs] to [toMs] (Kalshi's range is in whole seconds, both ends inclusive). */
    fun trades(
        ticker: String,
        fromMs: Long,
        toMs: Long,
    ): List<KalshiTrade> {
        val params =
            listOf(
                "ticker" to ticker,
                "min_ts" to "${fromMs / 1000}",
                "max_ts" to "${toMs / 1000}",
                "limit" to "$TRADES_PER_PAGE",
            )
        return Paging.cursor(
            fetch = { cursor ->
                val page = rest.get("/markets/trades", params + listOfNotNull(cursor?.let { "cursor" to it }))
                Page(page.objects("trades").map(KalshiMarketJson::trade), page.text("cursor"))
            },
            id = KalshiTrade::tradeId,
            time = KalshiTrade::timeMs,
        )
    }

    /** [ticker]'s candlesticks of [periodMinutes] (1, 60 or 1440) ending from [fromMs] to [toMs], oldest first. */
    fun candles(
        series: String,
        ticker: String,
        periodMinutes: Int,
        fromMs: Long,
        toMs: Long,
    ): List<KalshiCandle> {
        val params =
            listOf(
                "start_ts" to "${fromMs / 1000}",
                "end_ts" to "${toMs / 1000}",
                "period_interval" to "$periodMinutes",
            )
        val answer = rest.get("/series/$series/markets/$ticker/candlesticks", params)
        return answer.objects("candlesticks").map(KalshiMarketJson::candle).sortedBy { it.endMs }
    }

    private companion object {
        const val EVENTS_PER_PAGE = 200
        const val TRADES_PER_PAGE = 1000
        const val TICKERS_PER_CALL = 100
    }
}

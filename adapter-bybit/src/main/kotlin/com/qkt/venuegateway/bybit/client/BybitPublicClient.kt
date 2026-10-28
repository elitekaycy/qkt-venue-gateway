package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.cursor
import com.qkt.venuegateway.bybit.client.BybitJson.items
import com.qkt.venuegateway.support.JsonReads
import com.qkt.venuegateway.support.Page
import com.qkt.venuegateway.support.Paging

/**
 * Bybit's public market over [rest] (no key): listings, tickers, klines, funding rates, open interest, the
 * latest public trades and the order book, each of one category (`linear`, `spot`). Bybit answers time ranges
 * newest first and at most a page at a time, so ranges are walked here and answered oldest first.
 */
class BybitPublicClient(
    private val rest: BybitRest,
) {
    /** Every instrument of [category] Bybit lists, whatever its status, across pages. */
    fun instruments(category: String): List<BybitInstrument> =
        Paging.cursor(null, { cursor ->
            val params =
                listOf("category" to category, "limit" to "1000") + listOfNotNull(cursor?.let { "cursor" to it })
            val result = rest.get("/v5/market/instruments-info", params).result
            Page(result.items().map { BybitMarketJson.instrument(it, category) }, result.cursor())
        }, BybitInstrument::symbol) { 0L }

    /** [symbol]'s ticker now, stamped with Bybit's time; null when Bybit lists no such symbol. */
    fun ticker(
        category: String,
        symbol: String,
    ): BybitTicker? {
        val answer = rest.get("/v5/market/tickers", listOf("category" to category, "symbol" to symbol))
        return answer.result
            .items()
            .firstOrNull()
            ?.let { BybitMarketJson.ticker(it, answer.timeMs) }
    }

    /**
     * [symbol]'s klines of [interval] (`1`, `5`, `60`, `D`…, [intervalMs] long) starting from [fromMs] to [toMs],
     * oldest first; the newest may still be forming. [kind] is `kline`, `mark-price-kline` or
     * `index-price-kline`; only `kline` carries a volume. Asked for in spans of [KLINES_PER_CALL].
     */
    fun klines(
        category: String,
        symbol: String,
        kind: String,
        interval: String,
        intervalMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<BybitKline> =
        Paging.spans(fromMs, toMs + 1, intervalMs * KLINES_PER_CALL, { start, end ->
            val params =
                listOf("category" to category, "symbol" to symbol, "interval" to interval) +
                    listOf("start" to "$start", "end" to "${end - 1}", "limit" to "$KLINES_PER_CALL")
            BybitMarketJson.klines(rest.get("/v5/market/$kind", params).result, kind == "kline")
        }, BybitKline::startMs, BybitKline::startMs)

    /** [symbol]'s settled funding rates from [fromMs] to [toMs], oldest first. */
    fun fundingRates(
        category: String,
        symbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<BybitFundingRate> =
        Paging.backwards(fromMs, toMs, { end ->
            val params =
                listOf(
                    "category" to category,
                    "symbol" to symbol,
                    "startTime" to "$fromMs",
                    "endTime" to "$end",
                    "limit" to "200",
                )
            rest
                .get("/v5/market/funding/history", params)
                .result
                .items()
                .map(BybitMarketJson::fundingRate)
        }, BybitFundingRate::timeMs)

    /** [symbol]'s open interest taken every [interval] (`5min`) from [fromMs] to [toMs], oldest first. */
    fun openInterest(
        category: String,
        symbol: String,
        interval: String,
        fromMs: Long,
        toMs: Long,
    ): List<BybitOpenInterest> =
        Paging.backwards(fromMs, toMs, { end ->
            val params =
                listOf("category" to category, "symbol" to symbol, "intervalTime" to interval) +
                    listOf("startTime" to "$fromMs", "endTime" to "$end", "limit" to "200")
            rest
                .get("/v5/market/open-interest", params)
                .result
                .items()
                .map(BybitMarketJson::openInterest)
        }, BybitOpenInterest::timeMs)

    /** [symbol]'s latest public trades, at most [limit] (Bybit keeps 1000 of a contract, 60 of a spot pair), oldest first. */
    fun recentTrades(
        category: String,
        symbol: String,
        limit: Int,
    ): List<BybitPrint> {
        val params = listOf("category" to category, "symbol" to symbol, "limit" to "$limit")
        return rest.get("/v5/market/recent-trade", params).result.items().map(BybitMarketJson::recentTrade).sortedBy {
            it.timeMs
        }
    }

    /** [symbol]'s book now, [levels] a side, at Bybit's own stamp (`ts`). */
    fun orderBook(
        category: String,
        symbol: String,
        levels: Int,
    ): BybitBook {
        val result =
            rest
                .get(
                    "/v5/market/orderbook",
                    listOf(
                        "category" to category,
                        "symbol" to symbol,
                        "limit" to "$levels",
                    ),
                ).result
        return BybitMarketJson.book(result, JsonReads.run { result.long("ts") })
    }

    /** How far each call reaches. */
    companion object {
        /** The most klines Bybit answers a call. */
        const val KLINES_PER_CALL = 1000
    }
}

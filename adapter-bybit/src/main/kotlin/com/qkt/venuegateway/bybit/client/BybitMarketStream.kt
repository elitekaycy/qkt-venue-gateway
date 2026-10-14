package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.long
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Bybit's public socket of one category at [url]. A quoted symbol is subscribed to `tickers.<s>` (last, mark,
 * index, open interest; on contracts the best bid and ask too, the first push whole and the rest only what
 * changed) and `orderbook.1.<s>` (the best bid and ask, whole every push, the only place a spot pair's are),
 * merged into one [BybitTicker] per symbol and pushed to [onTicker] on every change. A taped symbol is
 * subscribed to `publicTrade.<s>` ([onTrades]) and, on contracts, `allLiquidation.<s>` ([onLiquidations]; Bybit
 * removed the older `liquidation.<s>`). Its link going down is the quote feed's ([onConnection]).
 */
class BybitMarketStream(
    url: String,
    private val contracts: Boolean,
    private val onTicker: (BybitTicker) -> Unit,
    private val onTrades: (symbol: String, List<BybitPrint>) -> Unit,
    private val onLiquidations: (symbol: String, List<BybitPrint>) -> Unit,
    onConnection: (Boolean, String) -> Unit,
) : AutoCloseable {
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val tickers = ConcurrentHashMap<String, BybitTicker>()
    private val socket = BybitSocket(url, "market", { wanted.toList() }, ::push, onConnection)

    fun start() = socket.start()

    /** Quotes [symbols] from now on (those already quoted stay). */
    fun quote(symbols: Collection<String>) = add(symbols.flatMap { listOf("tickers.$it", "orderbook.1.$it") })

    /** Tapes [symbols] from now on: their trades, and on contracts their liquidations. */
    fun tape(symbols: Collection<String>) =
        add(symbols.flatMap { listOfNotNull("publicTrade.$it", "allLiquidation.$it".takeIf { contracts }) })

    override fun close() = socket.close()

    private fun add(topics: List<String>) {
        val added = topics.filter(wanted::add)
        if (added.isNotEmpty()) socket.subscribe(added)
    }

    private fun push(
        topic: String,
        frame: JsonObject,
    ) {
        val symbol = topic.substringAfterLast('.')
        val timeMs = frame.long("ts")
        when (topic.substringBeforeLast('.')) {
            "tickers" ->
                ticker(
                    symbol,
                    timeMs,
                    frame["data"]!!.jsonObject,
                    BybitJson.run { frame.text("type") } == "snapshot",
                )
            "orderbook.1" -> best(symbol, timeMs, BybitMarketJson.book(frame["data"]!!.jsonObject, timeMs))
            "publicTrade" -> onTrades(symbol, items(frame).map(BybitMarketJson::streamTrade))
            "allLiquidation" -> onLiquidations(symbol, items(frame).map(BybitMarketJson::liquidation))
        }
    }

    private fun ticker(
        symbol: String,
        timeMs: Long,
        data: JsonObject,
        snapshot: Boolean,
    ) {
        val base = tickers[symbol]?.takeUnless { snapshot } ?: BybitTicker(symbol, timeMs)
        publish(BybitMarketJson.merge(base, data, timeMs))
    }

    private fun best(
        symbol: String,
        timeMs: Long,
        book: BybitBook,
    ) {
        val base = tickers[symbol] ?: BybitTicker(symbol, timeMs)
        val (bid, bidSize) = book.bids.firstOrNull() ?: NONE
        val (ask, askSize) = book.asks.firstOrNull() ?: NONE
        publish(
            base.copy(timeMs = maxOf(base.timeMs, timeMs), bid = bid, bidSize = bidSize, ask = ask, askSize = askSize),
        )
    }

    private fun publish(ticker: BybitTicker) {
        tickers[ticker.symbol] = ticker
        onTicker(ticker)
    }

    private fun items(frame: JsonObject) = (frame["data"] as JsonArray).map { it.jsonObject }

    private companion object {
        val NONE: Pair<BigDecimal?, BigDecimal?> = null to null
    }
}

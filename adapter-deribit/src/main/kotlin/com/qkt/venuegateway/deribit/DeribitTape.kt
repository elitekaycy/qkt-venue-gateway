package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPublicTrade
import com.qkt.venuegateway.deribit.client.TRADES_PER_CALL

/**
 * A contract's public tape and its liquidations (wire spec `/v1/trades`, `/v1/liquidations`) from Deribit's trade
 * history. Every print's `direction` is its taker's, the aggressor side. A print that liquidated a position carries
 * `liquidation`: `T` the taker was liquidated (the liquidation order took liquidity, on the print's own side), `M`
 * the maker (its resting order was on the other side), `MT` both. The tape is read a page at a time in time order,
 * each page starting at the millisecond the previous one ended in (its prints come in any order within it) and
 * repeats dropped by trade id; a page that cannot advance (a whole page in one millisecond) fails rather than loop.
 */
object DeribitTape {
    /**
     * The first [limit] prints of [code] in `[fromMs, toMs)`, oldest first; [perCall] is how many one call answers.
     * Cut at [limit], the last millisecond served may be missing prints that sort after the cut (the host serves
     * a full page only up to its last millisecond).
     */
    fun prints(
        history: DeribitMarketData,
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
        perCall: Int = TRADES_PER_CALL,
    ): List<VenuePrint> = walk(history, code, fromMs, toMs, minOf(limit, perCall), limit).take(limit).map(::print)

    /** Every print of [code] in `[fromMs, toMs)` that liquidated a position, oldest first, one per side liquidated. */
    fun liquidations(
        history: DeribitMarketData,
        code: String,
        fromMs: Long,
        toMs: Long,
        perCall: Int = TRADES_PER_CALL,
    ): List<VenuePrint> =
        walk(history, code, fromMs, toMs, perCall, Int.MAX_VALUE).flatMap { trade ->
            liquidatedSides(trade).map { side -> print(trade).copy(side = side) }
        }

    /**
     * The sides of the orders that liquidated a position in [trade]: the taker's for `T`, the maker's (the other)
     * for `M`, both for `MT`, none on a print that liquidated nobody. Any other mark is refused by name.
     */
    fun liquidatedSides(trade: DeribitPublicTrade): List<Side> {
        val taker = side(trade.direction)
        val maker = if (taker == Side.BUY) Side.SELL else Side.BUY
        return when (trade.liquidation) {
            null -> emptyList()
            "T" -> listOf(taker)
            "M" -> listOf(maker)
            "MT" -> listOf(taker, maker)
            else -> error("deribit trade ${trade.tradeId} has an unknown liquidation mark ${trade.liquidation}")
        }
    }

    private fun print(trade: DeribitPublicTrade) =
        VenuePrint(trade.tradeId, trade.timestampMs, trade.price, trade.amount, side(trade.direction))

    private fun side(direction: String) =
        when (direction) {
            "buy" -> Side.BUY
            "sell" -> Side.SELL
            else -> error("deribit trade direction $direction is not buy or sell")
        }

    /** Prints of `[fromMs, toMs)` in time order (within a millisecond by sequence), read until [enough] are held. */
    private fun walk(
        history: DeribitMarketData,
        code: String,
        fromMs: Long,
        toMs: Long,
        perCall: Int,
        enough: Int,
    ): List<DeribitPublicTrade> {
        val byId = LinkedHashMap<String, DeribitPublicTrade>()
        var start = fromMs
        while (start < toMs) {
            val page = history.tape(code, start, toMs - 1, perCall)
            page.items.filter { it.timestampMs in fromMs until toMs }.forEach { byId.putIfAbsent(it.tradeId, it) }
            if (!page.more || page.items.isEmpty() || byId.size >= enough) break
            val end = page.items.maxOf { it.timestampMs }
            check(end > start) { "deribit trades of $code fill a page within $start ms; they cannot be paged" }
            start = end
        }
        return byId.values.sortedWith(compareBy({ it.timestampMs }, { it.seq }))
    }
}

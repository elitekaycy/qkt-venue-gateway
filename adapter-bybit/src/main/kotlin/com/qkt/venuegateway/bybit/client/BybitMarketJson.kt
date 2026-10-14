package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.dec
import com.qkt.venuegateway.bybit.client.BybitJson.long
import com.qkt.venuegateway.bybit.client.BybitJson.need
import com.qkt.venuegateway.bybit.client.BybitJson.obj
import com.qkt.venuegateway.bybit.client.BybitJson.req
import com.qkt.venuegateway.bybit.client.BybitJson.text
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

/** Bybit's public market answers and pushes read into the client's types, decimals from their text. */
internal object BybitMarketJson {
    fun instrument(
        o: JsonObject,
        category: String,
    ): BybitInstrument {
        val lot = o.obj("lotSizeFilter")
        return BybitInstrument(
            symbol = o.need("symbol"),
            category = category,
            contractType = o.text("contractType"),
            status = o.need("status"),
            baseCoin = o.need("baseCoin"),
            quoteCoin = o.need("quoteCoin"),
            settleCoin = o.text("settleCoin") ?: o.need("quoteCoin"),
            tickSize = o.obj("priceFilter").req("tickSize"),
            qtyStep = lot.dec("qtyStep") ?: lot.req("basePrecision"),
            minOrderQty = lot.req("minOrderQty"),
            deliveryMs = o.text("deliveryTime")?.toLong()?.takeIf { it > 0 },
        )
    }

    /** A ticker answer or a full socket snapshot, as of [timeMs]. */
    fun ticker(
        o: JsonObject,
        timeMs: Long,
    ) = merge(BybitTicker(o.need("symbol"), timeMs), o, timeMs)

    /**
     * [base] with the fields [o] carries replacing its own: a linear ticker push after the first is a delta
     * holding only what changed.
     */
    fun merge(
        base: BybitTicker,
        o: JsonObject,
        timeMs: Long,
    ) = BybitTicker(
        symbol = base.symbol,
        timeMs = timeMs,
        bid = o.dec("bid1Price") ?: base.bid,
        bidSize = o.dec("bid1Size") ?: base.bidSize,
        ask = o.dec("ask1Price") ?: base.ask,
        askSize = o.dec("ask1Size") ?: base.askSize,
        last = o.dec("lastPrice") ?: base.last,
        mark = o.dec("markPrice") ?: base.mark,
        index = o.dec("indexPrice") ?: base.index,
        openInterest = o.dec("openInterest") ?: base.openInterest,
    )

    /** A kline answer's rows (newest first as Bybit sends them), oldest first; [volume] for trade klines only. */
    fun klines(
        result: JsonObject,
        volume: Boolean,
    ): List<BybitKline> =
        BybitJson
            .rows(result["list"])
            .map { r ->
                BybitKline(
                    r[0].toLong(),
                    BigDecimal(r[1]),
                    BigDecimal(r[2]),
                    BigDecimal(r[3]),
                    BigDecimal(r[4]),
                    if (volume) BigDecimal(r[5]) else null,
                )
            }.sortedBy { it.startMs }

    fun fundingRate(o: JsonObject) = BybitFundingRate(o.long("fundingRateTimestamp"), o.req("fundingRate"))

    fun openInterest(o: JsonObject) = BybitOpenInterest(o.long("timestamp"), o.req("openInterest"))

    /** One `/v5/market/recent-trade` item. */
    fun recentTrade(o: JsonObject) =
        BybitPrint(o.need("execId"), o.long("time"), o.req("price"), o.req("size"), o.need("side"))

    /** One `publicTrade` push item (`i`, `T`, `p`, `v`, `S`). */
    fun streamTrade(o: JsonObject) = BybitPrint(o.need("i"), o.long("T"), o.req("p"), o.req("v"), o.need("S"))

    /**
     * One `allLiquidation` push item (`T`, `s`, `S`, `v`, `p`). Bybit gives a liquidation no id, so its id is
     * its time, price and size, which no two liquidations of one contract share.
     */
    fun liquidation(o: JsonObject): BybitPrint {
        val time = o.long("T")
        val price = o.req("p")
        val size = o.req("v")
        return BybitPrint("$time-${price.toPlainString()}-${size.toPlainString()}", time, price, size, o.need("S"))
    }

    /** An order book answer (`/v5/market/orderbook`) or an `orderbook` snapshot push's `data`, at [timeMs]. */
    fun book(
        o: JsonObject,
        timeMs: Long,
    ) = BybitBook(timeMs, levels(o, "b"), levels(o, "a"))

    private fun levels(
        o: JsonObject,
        key: String,
    ) = BybitJson.rows(o[key]).map { BigDecimal(it[0]) to BigDecimal(it[1]) }
}

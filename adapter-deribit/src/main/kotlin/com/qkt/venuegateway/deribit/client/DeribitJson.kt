package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reading Deribit's JSON exactly: numbers come as JSON numbers (`83940.0`, `1e-06`), so each is read
 * from its literal text into a [BigDecimal], never through a double.
 */
internal object DeribitJson {
    fun decimal(element: JsonElement?): BigDecimal? {
        if (element == null || element is JsonNull) return null
        return BigDecimal((element as JsonPrimitive).content)
    }

    fun JsonObject.dec(key: String): BigDecimal? = decimal(this[key])

    fun JsonObject.req(key: String): BigDecimal = dec(key) ?: error("deribit field $key missing")

    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(key: String): Long = jsonPrimitive(key).content.toBigDecimal().toLong()

    private fun JsonObject.jsonPrimitive(key: String): JsonPrimitive =
        this[key]?.jsonPrimitive ?: error("deribit field $key missing")

    /** A side of the book: absent when Deribit sends a zero price or amount. */
    fun side(
        price: BigDecimal?,
        amount: BigDecimal?,
    ): Pair<BigDecimal?, BigDecimal?> =
        if (price == null ||
            amount == null ||
            price.signum() == 0 ||
            amount.signum() == 0
        ) {
            null to null
        } else {
            price to amount
        }

    fun instrument(o: JsonObject): DeribitInstrument {
        val perpetual = o.text("settlement_period") == "perpetual"
        return DeribitInstrument(
            name = o.text("instrument_name") ?: error("instrument_name missing"),
            kind = o.text("kind") ?: error("kind missing"),
            perpetual = perpetual,
            expiryMs = if (perpetual) null else o.long("expiration_timestamp"),
            strike = o.dec("strike"),
            optionType = o.text("option_type"),
            contractSize = o.req("contract_size"),
            tickSize = o.req("tick_size"),
            minTradeAmount = o.req("min_trade_amount"),
            settlementCurrency =
                o.text("settlement_currency") ?: o.text("counter_currency") ?: error("currency missing"),
            priceIndex = o.text("price_index") ?: error("price_index missing"),
            active = (o["is_active"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: error("is_active missing"),
        )
    }

    fun ticker(o: JsonObject): DeribitTicker {
        val (bid, bidAmount) = side(o.dec("best_bid_price"), o.dec("best_bid_amount"))
        val (ask, askAmount) = side(o.dec("best_ask_price"), o.dec("best_ask_amount"))
        return DeribitTicker(
            name = o.text("instrument_name") ?: error("instrument_name missing"),
            timestampMs = o.long("timestamp"),
            bid = bid,
            bidAmount = bidAmount,
            ask = ask,
            askAmount = askAmount,
            mark = o.dec("mark_price"),
            markIv = o.dec("mark_iv"),
            underlyingPrice = o.dec("underlying_price"),
            indexPrice = o.dec("index_price"),
            openInterest = o.dec("open_interest"),
        )
    }

    fun orderBook(o: JsonObject): DeribitOrderBook {
        fun levels(key: String) =
            o[key]!!.jsonArray.map {
                val (price, amount) = it.jsonArray.map(::decimal)
                price!! to amount!!
            }
        val name = o.text("instrument_name") ?: error("instrument_name missing")
        return DeribitOrderBook(name, o.long("timestamp"), levels("bids"), levels("asks"))
    }

    fun klines(o: JsonObject): List<DeribitKline> {
        val ticks = o["ticks"]!!.jsonArray

        fun col(key: String) = o[key]!!.jsonArray.map { decimal(it)!! }
        val (open, high, low, close, volume) = listOf(col("open"), col("high"), col("low"), col("close"), col("volume"))
        return ticks.indices.map { i ->
            DeribitKline(
                BigDecimal(ticks[i].jsonPrimitive.content).toLong(),
                open[i],
                high[i],
                low[i],
                close[i],
                volume[i],
            )
        }
    }

    fun fundingRate(o: JsonObject) = DeribitFundingRate(o.long("timestamp"), o.req("interest_1h"), o.req("index_price"))

    /** The trades of a `get_last_trades_by_instrument*` answer, as Deribit ordered them. */
    fun markTrades(o: JsonObject): List<DeribitMarkTrade> =
        o["trades"]!!.jsonArray.map {
            val t = it.jsonObject
            DeribitMarkTrade(t.long("trade_seq"), t.long("timestamp"), t.dec("mark_price"), t.dec("index_price"))
        }

    /** The prints of a `get_last_trades_by_instrument*` answer, as Deribit ordered them. */
    fun publicTrades(o: JsonObject): List<DeribitPublicTrade> =
        o["trades"]!!.jsonArray.map {
            val t = it.jsonObject
            DeribitPublicTrade(
                tradeId = t.text("trade_id") ?: error("deribit field trade_id missing"),
                seq = t.long("trade_seq"),
                timestampMs = t.long("timestamp"),
                price = t.req("price"),
                amount = t.req("amount"),
                direction = t.text("direction") ?: error("deribit field direction missing"),
                liquidation = t.text("liquidation"),
            )
        }

    fun JsonElement.obj(): JsonObject = jsonObject
}

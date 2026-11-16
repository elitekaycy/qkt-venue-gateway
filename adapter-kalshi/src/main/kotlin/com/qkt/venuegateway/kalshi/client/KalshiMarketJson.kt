package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.support.JsonReads.bool
import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.support.JsonReads.instantMs
import com.qkt.venuegateway.support.JsonReads.long
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.needDecimal
import com.qkt.venuegateway.support.JsonReads.objOrNull
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Kalshi's public market JSON read exactly: prices are `*_dollars` strings and counts `*_fp` strings, read as
 * [BigDecimal]; times are RFC 3339. Kalshi sends `""` for an unset string, which reads as absent.
 */
object KalshiMarketJson {
    fun series(o: JsonObject) =
        KalshiSeries(
            o.need("ticker"),
            o.text("category"),
            o.text("frequency"),
            o.text("fee_type"),
            o.decimal("fee_multiplier"),
        )

    fun event(o: JsonObject) =
        KalshiEvent(
            o.need("event_ticker"),
            o.need("series_ticker"),
            o.bool("mutually_exclusive") ?: false,
            o.text("fee_type_override"),
            o.decimal("fee_multiplier_override"),
            o.objects("markets").map(::market),
        )

    fun market(o: JsonObject) =
        KalshiMarket(
            ticker = o.need("ticker"),
            eventTicker = o.need("event_ticker"),
            status = o.need("status"),
            strikeType = o.text("strike_type"),
            floorStrike = o.decimal("floor_strike"),
            capStrike = o.decimal("cap_strike"),
            openTimeMs = time(o, "open_time"),
            closeTimeMs = time(o, "close_time"),
            expectedExpirationMs = o.instantMs("expected_expiration_time") ?: o.instantMs("latest_expiration_time"),
            occurrenceMs = o.instantMs("occurrence_datetime"),
            canCloseEarly = o.bool("can_close_early") ?: false,
            yesSubTitle = o.text("yes_sub_title"),
            result = o.text("result"),
            settlementValue = o.decimal("settlement_value_dollars"),
            priceRanges = o.objects("price_ranges").map(::priceRange),
            exchangeIndex = o.text("exchange_index")?.toInt(),
            yesBid = o.decimal("yes_bid_dollars"),
            yesAsk = o.decimal("yes_ask_dollars"),
            yesBidSize = o.decimal("yes_bid_size_fp"),
            yesAskSize = o.decimal("yes_ask_size_fp"),
            openInterest = o.decimal("open_interest_fp"),
            mveCollection = o.text("mve_collection_ticker"),
        )

    fun priceRange(o: JsonObject) =
        KalshiPriceRange(o.needDecimal("start"), o.needDecimal("end"), o.needDecimal("step"))

    /** `GET /markets/{ticker}/orderbook`: `orderbook_fp`'s `yes_dollars` and `no_dollars`, an empty side absent. */
    fun book(o: JsonObject): KalshiBook {
        val book = o.objOrNull("orderbook_fp") ?: error("kalshi orderbook without orderbook_fp (has ${o.keys})")
        return KalshiBook(levels(book["yes_dollars"]), levels(book["no_dollars"]))
    }

    fun trade(o: JsonObject) =
        KalshiTrade(
            o.need("trade_id"),
            o.need("ticker"),
            o.needDecimal("count_fp"),
            o.needDecimal("yes_price_dollars"),
            o.need("taker_book_side"),
            time(o, "created_time"),
        )

    fun candle(o: JsonObject) =
        KalshiCandle(
            o.long("end_period_ts") * 1000,
            ohlc(o.objOrNull("yes_bid")),
            ohlc(o.objOrNull("yes_ask")),
            o.needDecimal("volume_fp"),
            o.decimal("open_interest_fp"),
        )

    /** A `ticker` push's `msg`. */
    fun ticker(o: JsonObject) =
        KalshiTicker(
            o.need("market_ticker"),
            o.decimal("yes_bid_dollars"),
            o.decimal("yes_ask_dollars"),
            o.decimal("yes_bid_size_fp"),
            o.decimal("yes_ask_size_fp"),
            o.text("ts_ms")?.toLong() ?: (o.long("ts") * 1000),
        )

    private fun ohlc(o: JsonObject?) =
        KalshiOhlc(
            o?.decimal("open_dollars"),
            o?.decimal("high_dollars"),
            o?.decimal("low_dollars"),
            o?.decimal("close_dollars"),
        )

    private fun levels(element: Any?): List<Pair<BigDecimal, BigDecimal>> =
        (element as? JsonArray ?: return emptyList()).map { level ->
            val cells = (level as JsonArray).map { (it as JsonPrimitive).content }
            BigDecimal(cells[0]) to BigDecimal(cells[1])
        }

    private fun time(
        o: JsonObject,
        key: String,
    ): Long = OffsetDateTime.parse(o.need(key)).toInstant().toEpochMilli()
}

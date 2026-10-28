package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.support.JsonReads.text
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Alpaca's market data API in its own words: bars of a share (`/v2/stocks/bars`, from the account's [feed]) or of
 * a coin pair (`/v1beta3/crypto/us/bars`), page by page on `next_page_token`.
 */
class AlpacaData(
    private val http: AlpacaHttp,
    private val feed: String,
) {
    /** [symbol]'s bars of [timeframe] (`1Min`, `1Hour`, `1Day`) starting in `[fromMs, toMs)`, oldest first. */
    fun bars(
        symbol: String,
        crypto: Boolean,
        timeframe: String,
        fromMs: Long,
        toMs: Long,
    ): List<JsonObject> {
        val path = if (crypto) "/v1beta3/crypto/us/bars" else "/v2/stocks/bars"
        val out = mutableListOf<JsonObject>()
        var token: String? = null
        while (true) {
            val params =
                listOf(
                    "symbols" to symbol,
                    "timeframe" to timeframe,
                    "start" to Instant.ofEpochMilli(fromMs).toString(),
                    "end" to Instant.ofEpochMilli(toMs - 1).toString(),
                    "limit" to "10000",
                    "sort" to "asc",
                ) + listOfNotNull(("feed" to feed).takeUnless { crypto }, token?.let { "page_token" to it })
            val page = AlpacaJson.obj(http.get(path, params), "bars")
            out += ((page["bars"] as? JsonObject)?.get(symbol) as? JsonArray).orEmpty().map { it as JsonObject }
            token = page.text("next_page_token") ?: return out
        }
    }
}

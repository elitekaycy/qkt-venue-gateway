package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.deribit.client.DeribitJson.obj
import java.io.IOException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Deribit's public JSON-RPC over HTTPS at [baseUrl] (`https://www.deribit.com`, or
 * `https://test.deribit.com` for testnet): listings, tickers, klines and delivery prices, no account.
 * A JSON-RPC error is [DeribitException]; the venue being unreachable is an [IOException].
 */
class DeribitPublicClient(
    private val baseUrl: String = "https://www.deribit.com",
    timeoutMs: Long = 10_000,
) : DeribitMarketData {
    private val http = OkHttpClient.Builder().callTimeout(Duration.ofMillis(timeoutMs)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** The live instruments of [currency] (`USDC`) of [kind] (`future`, `option`). */
    override fun instruments(
        currency: String,
        kind: String,
    ): List<DeribitInstrument> =
        call("get_instruments", "currency" to currency, "kind" to kind, "expired" to "false")
            .jsonArray
            .map { DeribitJson.instrument(it.jsonObject) }

    override fun instrument(name: String): DeribitInstrument =
        DeribitJson.instrument(
            call(
                "get_instrument",
                "instrument_name" to name,
            ).obj(),
        )

    override fun ticker(name: String): DeribitTicker =
        DeribitJson.ticker(call("ticker", "instrument_name" to name).obj())

    /** The klines of [name], [minutes] long, starting in `[fromMs, toMs]`; the last may still be forming. */
    override fun klines(
        name: String,
        minutes: Long,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitKline> =
        DeribitJson.klines(
            call(
                "get_tradingview_chart_data",
                "instrument_name" to name,
                "resolution" to resolution(minutes),
                "start_timestamp" to fromMs.toString(),
                "end_timestamp" to toMs.toString(),
            ).obj(),
        )

    /** The most recent [count] daily delivery prices of [index] (`btc_usdc`), newest first. */
    override fun deliveryPrices(
        index: String,
        count: Int,
    ): List<Pair<LocalDate, BigDecimal>> =
        call("get_delivery_prices", "index_name" to index, "count" to count.toString())
            .obj()["data"]!!
            .jsonArray
            .map { row ->
                val o = row.jsonObject
                LocalDate.parse(o["date"]!!.jsonPrimitive.content) to DeribitJson.decimal(o["delivery_price"])!!
            }

    private fun resolution(minutes: Long): String =
        when (minutes) {
            1L, 3L, 5L, 10L, 15L, 30L, 60L, 120L, 180L, 360L, 720L -> minutes.toString()
            1_440L -> "1D"
            else -> throw IllegalArgumentException("Deribit has no $minutes-minute klines")
        }

    private fun call(
        method: String,
        vararg params: Pair<String, String>,
    ): JsonElement {
        val url =
            "$baseUrl/api/v2/public/$method"
                .toHttpUrl()
                .newBuilder()
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val text = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val envelope =
            json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("deribit $method: not JSON: ${text.take(200)}")
        envelope["error"]?.jsonObject?.let { e ->
            throw DeribitException(
                e["code"]?.jsonPrimitive?.int ?: 0,
                e["message"]?.jsonPrimitive?.content ?: "unknown",
            )
        }
        return envelope["result"] ?: throw IOException("deribit $method: no result")
    }
}

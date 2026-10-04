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
 * `https://test.deribit.com` for testnet): listings, tickers, klines, funding rates and delivery prices,
 * no account.
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

    /**
     * The klines of [name], [minutes] long, from the one holding [fromMs] to [toMs], oldest first; the
     * last may still be forming. Deribit answers at most 5001 klines, the newest, without saying it cut
     * the rest, so the range is asked for in spans of [KLINES_PER_CALL] and joined, each kline once.
     */
    override fun klines(
        name: String,
        minutes: Long,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitKline> {
        val resolution = resolution(minutes)
        val span = minutes * MINUTE_MS * KLINES_PER_CALL
        val byStart = sortedMapOf<Long, DeribitKline>()
        var start = fromMs
        while (start <= toMs) {
            val end = minOf(toMs, start + span - 1)
            val answer =
                call(
                    "get_tradingview_chart_data",
                    "instrument_name" to name,
                    "resolution" to resolution,
                    "start_timestamp" to start.toString(),
                    "end_timestamp" to end.toString(),
                )
            DeribitJson.klines(answer.obj()).forEach { byStart.putIfAbsent(it.startMs, it) }
            start = end + 1
        }
        return byStart.values.toList()
    }

    /**
     * Deribit answers at most about 740 hours of funding, the newest, without saying it cut the rest, so
     * the range is asked for in spans of [FUNDING_HOURS_PER_CALL] and joined, each hour once.
     */
    override fun fundingRates(
        name: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitFundingRate> {
        val byTime = sortedMapOf<Long, DeribitFundingRate>()
        var start = fromMs
        while (start <= toMs) {
            val end = minOf(toMs, start + FUNDING_HOURS_PER_CALL * HOUR_MS - 1)
            call(
                "get_funding_rate_history",
                "instrument_name" to name,
                "start_timestamp" to start.toString(),
                "end_timestamp" to end.toString(),
            ).jsonArray
                .map { DeribitJson.fundingRate(it.jsonObject) }
                .filter { it.timestampMs in start..end }
                .forEach { byTime.putIfAbsent(it.timestampMs, it) }
            start = end + 1
        }
        return byTime.values.toList()
    }

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

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L

        /** Hours of funding asked for per call, safely under Deribit's cap of about 740. */
        const val FUNDING_HOURS_PER_CALL = 720L

        /** Klines asked for per call, safely under Deribit's 5001 cap. */
        const val KLINES_PER_CALL = 4_000L
    }

    private fun resolution(minutes: Long): String =
        when (minutes) {
            !in DERIBIT_KLINE_MINUTES -> throw IllegalArgumentException("Deribit has no $minutes-minute klines")
            1_440L -> "1D"
            else -> minutes.toString()
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

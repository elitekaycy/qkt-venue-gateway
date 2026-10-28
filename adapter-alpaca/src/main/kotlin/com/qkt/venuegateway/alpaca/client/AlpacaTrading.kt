package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.support.JsonReads.text
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Alpaca's trading API (`/v2`) in its own words: the account, its assets, positions and orders, and the FILL
 * activities that list executions. Every answer is Alpaca's JSON; a lookup Alpaca answers 404 is null. The whole
 * asset list (fourteen thousand shares, megabytes) is read through [bulk], which may wait longer than an order should.
 */
class AlpacaTrading(
    private val http: AlpacaHttp,
    private val bulk: AlpacaHttp = http,
) {
    fun account(): JsonObject = AlpacaJson.obj(http.get("/v2/account"), "account")

    /** The active assets of [assetClass] (`us_equity`, `crypto`). */
    fun assets(assetClass: String): List<JsonObject> =
        AlpacaJson
            .array(bulk.get("/v2/assets", listOf("status" to "active", "asset_class" to assetClass)), "assets")
            .map { it as JsonObject }

    /** One asset by its symbol (`AAPL`, `BTCUSD`), or null when Alpaca has none. */
    fun asset(symbol: String): JsonObject? = missingAsNull { AlpacaJson.obj(http.get("/v2/assets/$symbol"), "asset") }

    fun positions(): List<JsonObject> =
        AlpacaJson.array(http.get("/v2/positions"), "positions").map { it as JsonObject }

    /** Every open order, oldest first (Alpaca lists at most 500). */
    fun openOrders(): List<JsonObject> =
        AlpacaJson
            .array(http.get("/v2/orders", listOf("status" to "open", "limit" to "500", "direction" to "asc")), "orders")
            .map { it as JsonObject }

    fun orderByClientId(clientOrderId: String): JsonObject? =
        missingAsNull {
            AlpacaJson.obj(
                http.get(
                    "/v2/orders:by_client_order_id",
                    listOf(
                        "client_order_id" to clientOrderId,
                    ),
                ),
                "order",
            )
        }

    fun order(id: String): JsonObject? = missingAsNull { AlpacaJson.obj(http.get("/v2/orders/$id"), "order") }

    fun submit(body: JsonObject): JsonObject = AlpacaJson.obj(http.send("POST", "/v2/orders", body.toString()), "order")

    /** Replaces order [id]; Alpaca answers the new order, which replaces it. */
    fun replace(
        id: String,
        body: JsonObject,
    ): JsonObject = AlpacaJson.obj(http.send("PATCH", "/v2/orders/$id", body.toString()), "replace")

    /** Asks Alpaca to cancel order [id]; false when it has no such order. */
    fun cancel(id: String): Boolean = missingAsNull { http.delete("/v2/orders/$id") } != null

    /**
     * FILL activities from [fromMs] to [toMs], oldest first, page by page (Alpaca pages by the last activity's id).
     */
    fun fills(
        fromMs: Long,
        toMs: Long,
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var token: String? = null
        while (true) {
            val params =
                listOf(
                    "after" to Instant.ofEpochMilli(fromMs).toString(),
                    "until" to Instant.ofEpochMilli(toMs).toString(),
                    "direction" to "asc",
                    "page_size" to PAGE.toString(),
                ) + listOfNotNull(token?.let { "page_token" to it })
            val page =
                AlpacaJson
                    .array(
                        http.get("/v2/account/activities/FILL", params),
                        "activities",
                    ).map { it as JsonObject }
            out += page
            if (page.size < PAGE) return out
            token = page.last().text("id")
        }
    }

    private fun <T> missingAsNull(call: () -> T): T? =
        try {
            call()
        } catch (e: AlpacaHttpException) {
            if (e.status == NOT_FOUND) null else throw e
        }

    private companion object {
        const val PAGE = 100
        const val NOT_FOUND = 404
    }
}

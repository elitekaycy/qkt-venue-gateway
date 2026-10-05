package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.cursor
import com.qkt.venuegateway.bybit.client.BybitJson.items
import java.math.BigDecimal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Bybit unified account's REST calls over [rest] (signed), every one of one [category]: orders by their
 * `orderLinkId`, executions, positions and the wallet. [settleCoin] narrows the account-wide reads of a
 * contract category (Bybit requires it there); a spot account reads without it.
 */
class BybitPrivateClient(
    private val rest: BybitRest,
    private val category: String,
    private val settleCoin: String?,
) {
    /** Sends [order]; Bybit answers only its ids, so the order as it then stands is read back by [orderByLink]. */
    fun place(order: BybitNewOrder): String =
        rest.post("/v5/order/create", BybitPrivateJson.create(order)).result.let {
            BybitJson.run { it.need("orderId") }
        }

    /** Cancels [symbol]'s order [orderLinkId]; a [BybitException] when Bybit holds no open order of it. */
    fun cancel(
        symbol: String,
        orderLinkId: String,
    ) {
        rest.post("/v5/order/cancel", buildJsonObject { base(symbol, orderLinkId) })
    }

    /** Changes [symbol]'s working order [orderLinkId]: any of its quantity, limit and trigger price. */
    fun amend(
        symbol: String,
        orderLinkId: String,
        qty: BigDecimal?,
        price: BigDecimal?,
        triggerPrice: BigDecimal?,
    ) {
        val body =
            buildJsonObject {
                base(symbol, orderLinkId)
                qty?.let { put("qty", it.toPlainString()) }
                price?.let { put("price", it.toPlainString()) }
                triggerPrice?.let { put("triggerPrice", it.toPlainString()) }
            }
        rest.post("/v5/order/amend", body)
    }

    /**
     * The order [orderLinkId] as Bybit has it now: from the live orders (which also hold ones that ended in the
     * last minutes), else from the order history; null when Bybit holds neither.
     */
    fun orderByLink(orderLinkId: String): BybitOrder? {
        val params = listOf("category" to category, "orderLinkId" to orderLinkId)
        return listOf("/v5/order/realtime", "/v5/order/history").firstNotNullOfOrNull { path ->
            rest
                .get(path, params, signed = true)
                .result
                .items()
                .map(BybitPrivateJson::order)
                .filter { it.orderLinkId == orderLinkId }
                .maxByOrNull { it.updatedMs }
        }
    }

    /** Every working order of the account in [category] (conditional ones included), across pages. */
    fun openOrders(): List<BybitOrder> =
        pages("/v5/order/realtime", listOf("openOnly" to "0", "limit" to "50")).map(BybitPrivateJson::order)

    /**
     * The account's executions from [fromMs] to [toMs], oldest first, of [execType] only when given. Bybit
     * answers at most seven days a call, so a longer range is read seven days at a time.
     */
    fun executions(
        fromMs: Long,
        toMs: Long,
        execType: String? = null,
    ): List<BybitExecution> {
        val all = mutableListOf<BybitExecution>()
        var start = fromMs
        while (start <= toMs) {
            val end = minOf(toMs, start + SEVEN_DAYS_MS - 1)
            val params =
                listOf("startTime" to "$start", "endTime" to "$end", "limit" to "100") +
                    listOfNotNull(execType?.let { "execType" to it })
            pages("/v5/execution/list", params).mapTo(all, BybitPrivateJson::execution)
            start = end + 1
        }
        return all.distinctBy { it.execId }.sortedBy { it.execTimeMs }
    }

    /** The account's open positions in [category]. */
    fun positions(): List<BybitPosition> =
        pages("/v5/position/list", listOf("limit" to "200")).map(BybitPrivateJson::position)

    /** [symbol]'s position slots, flat ones included: one (index 0) in one-way mode, two (1 and 2) in hedge mode. */
    fun positionSlots(symbol: String): List<BybitPosition> {
        val params = listOf("category" to category, "symbol" to symbol)
        return rest
            .get("/v5/position/list", params, signed = true)
            .result
            .items()
            .map(BybitPrivateJson::position)
    }

    /** The unified account's wallet, with [coin]'s own figures. */
    fun wallet(coin: String): BybitWallet {
        val result =
            rest
                .get(
                    "/v5/account/wallet-balance",
                    listOf("accountType" to "UNIFIED", "coin" to coin),
                    signed = true,
                ).result
        return BybitPrivateJson.wallet(result.items().firstOrNull() ?: error("bybit answered no unified wallet"), coin)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.base(
        symbol: String,
        orderLinkId: String,
    ) {
        put("category", category)
        put("symbol", symbol)
        put("orderLinkId", orderLinkId)
    }

    /** Every item of a paged account read of [path], following `nextPageCursor`. */
    private fun pages(
        path: String,
        params: List<Pair<String, String>>,
    ): List<kotlinx.serialization.json.JsonObject> {
        val scope = listOf("category" to category) + listOfNotNull(settleCoin?.let { "settleCoin" to it })
        val all = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var cursor: String? = null
        do {
            val result =
                rest
                    .get(
                        path,
                        scope + params + listOfNotNull(cursor?.let { "cursor" to it }),
                        signed = true,
                    ).result
            val items = result.items()
            all += items
            cursor = result.cursor()?.takeIf { items.isNotEmpty() }
        } while (cursor != null && all.size < MAX_ITEMS)
        return all
    }

    private companion object {
        const val SEVEN_DAYS_MS = 7 * 86_400_000L
        const val MAX_ITEMS = 50_000
    }
}

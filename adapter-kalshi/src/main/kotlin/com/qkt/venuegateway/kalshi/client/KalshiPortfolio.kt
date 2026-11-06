package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.Page
import com.qkt.venuegateway.support.Paging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The account's calls over a signed [rest]: the balance, positions, orders, fills and settlements, and V2 order
 * entry (create, amend, cancel). Kalshi finds an order only by its id, never by its `client_order_id`, so [orders]
 * is how a label is found when its id is not known. Lists are paged by `cursor`; query times are Unix seconds.
 */
class KalshiPortfolio(
    private val rest: KalshiRest,
) {
    fun balance(): KalshiBalance = KalshiPortfolioJson.balance(rest.get("/portfolio/balance"))

    /** Every market position held now (unsettled, non-zero). */
    fun positions(): List<KalshiPosition> =
        pages("/portfolio/positions", listOf("count_filter" to "position"), "market_positions")
            .map(KalshiPortfolioJson::position)

    /** The account's orders, every status unless [status] names one (`resting`, `canceled`, `executed`). */
    fun orders(status: String? = null): List<KalshiOrder> =
        pages("/portfolio/orders", listOfNotNull(status?.let { "status" to it }), "orders")
            .map(KalshiPortfolioJson::order)

    /** The order [orderId], or null when Kalshi has none by it. */
    fun order(orderId: String): KalshiOrder? =
        rest.orNull { rest.get("/portfolio/orders/$orderId") }?.let {
            KalshiPortfolioJson.order(it["order"] as JsonObject)
        }

    /** The account's fills from [fromMs] to [toMs] (whole seconds). */
    fun fills(
        fromMs: Long,
        toMs: Long,
    ): List<KalshiFill> = pages("/portfolio/fills", range(fromMs, toMs), "fills").map(KalshiPortfolioJson::fill)

    /** The account's settlements from [fromMs] to [toMs] (whole seconds). */
    fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<KalshiSettlement> =
        pages("/portfolio/settlements", range(fromMs, toMs), "settlements").map(KalshiPortfolioJson::settlement)

    fun place(order: KalshiNewOrder): KalshiPlaced =
        KalshiPortfolioJson.placed(rest.post("/portfolio/events/orders", KalshiPortfolioJson.body(order)))

    /**
     * Amends [order] to [count] contracts in all (filled plus resting) at the YES [price]; Kalshi keeps the order id
     * and the label.
     */
    fun amend(
        order: KalshiOrder,
        count: java.math.BigDecimal,
        price: java.math.BigDecimal,
    ) {
        val body =
            buildJsonObject {
                put("ticker", order.ticker)
                put("side", order.bookSide)
                put("price", price.toPlainString())
                put("count", count.toPlainString())
                order.clientOrderId?.let {
                    put("client_order_id", it)
                    put("updated_client_order_id", it)
                }
            }
        rest.post("/portfolio/events/orders/${order.orderId}/amend", body)
    }

    fun cancel(orderId: String) {
        rest.delete("/portfolio/events/orders/$orderId")
    }

    private fun range(
        fromMs: Long,
        toMs: Long,
    ) = listOf("min_ts" to "${fromMs / 1000}", "max_ts" to "${toMs / 1000}")

    private fun pages(
        path: String,
        params: List<Pair<String, String>>,
        key: String,
    ): List<JsonObject> {
        var n = 0
        return Paging.cursor(
            fetch = { cursor ->
                val page = rest.get(path, params + ("limit" to "$PAGE") + listOfNotNull(cursor?.let { "cursor" to it }))
                Page(page.objects(key), page.text("cursor"))
            },
            id = { n++ },
            time = { 0L },
        )
    }

    private companion object {
        const val PAGE = 200
    }
}

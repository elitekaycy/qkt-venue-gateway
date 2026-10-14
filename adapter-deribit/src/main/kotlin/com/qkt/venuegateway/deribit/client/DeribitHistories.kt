package com.qkt.venuegateway.deribit.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The account histories Deribit pages newest first by a continuation (the transaction log, the
 * settlement history), read over one private [call]. Every page is read until Deribit gives no
 * continuation, a page comes back empty, or the page reached far enough back; a continuation that does
 * not advance fails rather than loop. Rows come back oldest first.
 */
internal class DeribitHistories(
    private val call: (String, JsonObject) -> JsonElement,
) {
    /** The transaction log from [fromMs] to [toMs], which Deribit filters by that window itself. */
    fun transactions(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitTransaction> =
        collect<DeribitTransaction, Long> { continuation ->
            DeribitPrivateJson.transactionPage(
                call(
                    "private/get_transaction_log",
                    buildJsonObject {
                        put("currency", currency)
                        put("start_timestamp", fromMs)
                        put("end_timestamp", toMs)
                        put("count", PAGE)
                        continuation?.let { put("continuation", it) }
                    },
                ).jsonObject,
            )
        }.distinctBy { it.id }.sortedWith(compareBy({ it.timestampMs }, { it.id }))

    /**
     * The settlement history from [fromMs] to [toMs]. Deribit only takes the newest instant to start from
     * (inclusive) and refuses `type=exercise`, so every type is read back past [fromMs] and filtered here.
     */
    fun settlements(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitSettlement> =
        collect<DeribitSettlement, String>({ page -> page.last().timestampMs < fromMs }) { continuation ->
            DeribitPrivateJson.settlementPage(
                call(
                    "private/get_settlement_history_by_currency",
                    buildJsonObject {
                        put("currency", currency)
                        put("search_start_timestamp", toMs)
                        put("count", PAGE)
                        continuation?.let { put("continuation", it) }
                    },
                ).jsonObject,
            )
        }.filter { it.timestampMs in fromMs..toMs }.sortedBy { it.timestampMs }

    private fun <T, C : Any> collect(
        done: (List<T>) -> Boolean = { false },
        page: (continuation: C?) -> Pair<List<T>, C?>,
    ): List<T> {
        val rows = mutableListOf<T>()
        var continuation: C? = null
        do {
            val (items, next) = page(continuation)
            rows += items
            check(next == null || next != continuation) { "deribit history does not advance past $next" }
            continuation = next.takeIf { items.isNotEmpty() && !done(items) }
        } while (continuation != null)
        return rows
    }

    private companion object {
        const val PAGE = 250
    }
}

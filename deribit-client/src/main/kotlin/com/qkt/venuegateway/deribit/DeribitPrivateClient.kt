package com.qkt.venuegateway.deribit

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Deribit's private API over one authenticated [DeribitSocket] at [url] (`wss://test.deribit.com/ws/api/v2`
 * for testnet). On every connect it authenticates with the API key [clientId]/[clientSecret] and
 * subscribes the account's order and trade channels of [currencies] for futures and options, failing
 * the connect unless Deribit confirms every channel (it can drop one without an error). Pushes reach
 * [onOrder] and [onTrade]; [onConnection] hears the link go up and down. The secret is only ever sent
 * to Deribit.
 */
class DeribitPrivateClient(
    url: String,
    private val clientId: String,
    private val clientSecret: String,
    private val currencies: Set<String>,
    private val onOrder: (DeribitOrder) -> Unit,
    private val onTrade: (DeribitTrade) -> Unit,
    onConnection: (Boolean, String) -> Unit,
    requestTimeoutMs: Long = 10_000,
) : DeribitTrading {
    private val channels =
        currencies.flatMap { c ->
            KINDS.flatMap { k -> listOf("user.orders.$k.$c.raw", "user.trades.$k.$c.raw") }
        }
    private val socket =
        DeribitSocket(url, "private", HEARTBEAT_SECONDS, requestTimeoutMs, ::onNotification, onConnection, ::open)

    override fun start() = socket.start()

    override fun close() = socket.close()

    override fun account(currency: String) =
        DeribitPrivateJson.account(call("private/get_account_summary") { put("currency", currency) }.jsonObject)

    override fun positions(currency: String) =
        call(
            "private/get_positions",
        ) { put("currency", currency) }.jsonArray.map { DeribitPrivateJson.position(it.jsonObject) }

    override fun openOrders(currency: String) =
        call("private/get_open_orders_by_currency") {
            put("currency", currency)
        }.jsonArray.map { DeribitPrivateJson.order(it.jsonObject) }

    override fun ordersByLabel(
        currency: String,
        label: String,
    ) = call("private/get_order_state_by_label") {
        put("currency", currency)
        put("label", label)
    }.jsonArray.map { DeribitPrivateJson.order(it.jsonObject) }

    override fun place(order: DeribitNewOrder): DeribitOrder {
        val answer =
            call(if (order.direction == "buy") "private/buy" else "private/sell") {
                put("instrument_name", order.instrument)
                put("amount", order.amount.number())
                put("type", order.type)
                put("label", order.label)
                put("time_in_force", order.timeInForce)
                put("reduce_only", order.reduceOnly)
                order.price?.let { put("price", it.number()) }
                order.triggerPrice?.let { put("trigger_price", it.number()) }
                order.trigger?.let { put("trigger", it) }
            }
        return DeribitPrivateJson.order(answer.jsonObject["order"]!!.jsonObject)
    }

    override fun cancelByLabel(
        currency: String,
        label: String,
    ) = call("private/cancel_by_label") {
        put("currency", currency)
        put("label", label)
    }.jsonPrimitive.int

    override fun editByLabel(
        label: String,
        instrument: String,
        amount: BigDecimal,
        price: BigDecimal?,
        triggerPrice: BigDecimal?,
    ): DeribitOrder {
        val answer =
            call("private/edit_by_label") {
                put("label", label)
                put("instrument_name", instrument)
                put("amount", amount.number())
                price?.let { put("price", it.number()) }
                triggerPrice?.let { put("trigger_price", it.number()) }
            }
        return DeribitPrivateJson.order(answer.jsonObject["order"]!!.jsonObject)
    }

    override fun trades(
        currency: String,
        fromMs: Long,
        toMs: Long,
    ): List<DeribitTrade> =
        DeribitTradeHistory.collect(fromMs, toMs) { start ->
            DeribitPrivateJson.tradePage(
                call("private/get_user_trades_by_currency_and_time") {
                    put("currency", currency)
                    put("start_timestamp", start)
                    put("end_timestamp", toMs)
                    put("count", PAGE)
                    put("sorting", "asc")
                }.jsonObject,
            )
        }

    private fun open(call: (String, JsonObject) -> JsonElement) {
        call(
            "public/auth",
            buildJsonObject {
                put("grant_type", "client_credentials")
                put("client_id", clientId)
                put("client_secret", clientSecret)
            },
        )
        val confirmed =
            call("private/subscribe", JsonObject(mapOf("channels" to JsonArray(channels.map(::JsonPrimitive)))))
                .jsonArray
                .map { it.jsonPrimitive.content }
        val missing = channels - confirmed.toSet()
        check(missing.isEmpty()) { "deribit did not confirm the subscriptions $missing" }
    }

    private fun onNotification(
        channel: String,
        data: JsonElement,
    ) {
        val items = (data as? JsonArray) ?: listOf(data)
        when {
            channel.startsWith("user.orders.") -> items.forEach { onOrder(DeribitPrivateJson.order(it.jsonObject)) }
            channel.startsWith("user.trades.") -> items.forEach { onTrade(DeribitPrivateJson.trade(it.jsonObject)) }
        }
    }

    private fun call(
        method: String,
        params: JsonObjectBuilder.() -> Unit,
    ) = socket.request(method, buildJsonObject(params))

    /** [this] as a JSON number written from its exact text. */
    private fun BigDecimal.number() = JsonPrimitive(this)

    private companion object {
        val KINDS = listOf("future", "option")
        const val HEARTBEAT_SECONDS = 30
        const val PAGE = 1000
    }
}

package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.support.Hmac
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/**
 * One account's private socket at [url] for [category]: authenticated with [key] (signed with [secret], the
 * signature good until 10 s after [clock]'s now), subscribed to `order.<category>` and
 * `execution.<category>`, so Bybit pushes this category's orders ([onOrder]) and executions ([onExecution])
 * only. Its link going up and down is the venue connection ([onConnection]).
 */
class BybitAccountStream(
    url: String,
    private val key: String,
    private val secret: String,
    private val clock: () -> Long,
    category: String,
    private val onOrder: (BybitOrder) -> Unit,
    private val onExecution: (BybitExecution) -> Unit,
    http: OkHttpClient = OkHttpClient(),
    onConnection: (Boolean, String) -> Unit,
) : AutoCloseable {
    private val orderTopic = "order.$category"
    private val executionTopic = "execution.$category"
    private val socket =
        BybitSocket(url, "account", { listOf(orderTopic, executionTopic) }, ::push, onConnection, ::auth, http = http)

    fun start() = socket.start()

    override fun close() = socket.close()

    private fun auth(): String {
        val expires = clock() + 10_000
        return """{"op":"auth","args":["$key",$expires,"${Hmac.sha256Hex(secret, "GET/realtime$expires")}"]}"""
    }

    private fun push(
        topic: String,
        frame: JsonObject,
    ) {
        val items = (frame["data"] as? JsonArray ?: return).map { it.jsonObject }
        when (topic) {
            orderTopic -> items.map(BybitPrivateJson::order).forEach(onOrder)
            executionTopic -> items.map(BybitPrivateJson::execution).forEach(onExecution)
        }
    }

    override fun toString() = "BybitAccountStream($executionTopic)"
}

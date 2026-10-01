package com.qkt.venuegateway.deribit.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Deribit's public ticker channels (`ticker.<instrument>.<interval>`) over a [DeribitSocket] at [url].
 * [subscribe] sets the instruments wanted; every ticker notification reaches [onTicker]. Each change is
 * sent as it is made and everything wanted is subscribed again on every connect, so no change made
 * while reconnecting is lost (a repeated subscription is harmless). [onConnection] hears each drop and
 * return.
 */
class DeribitTickerStream(
    url: String = "wss://www.deribit.com/ws/api/v2",
    private val onTicker: (DeribitTicker) -> Unit,
    onConnection: (Boolean, String) -> Unit,
    private val interval: String = "100ms",
) : DeribitTickers {
    private val lock = Any()
    private var wanted: Set<String> = emptySet()
    private val socket: DeribitSocket =
        DeribitSocket(
            url,
            "tickers",
            onNotification = ::onNotification,
            onConnection = onConnection,
            onOpen = { resubscribe() },
        )

    override fun start() = socket.start()

    /** Wants exactly [names]' tickers from now on. */
    override fun subscribe(names: Set<String>) =
        synchronized(lock) {
            val added = names - wanted
            val removed = wanted - names
            wanted = names
            if (added.isNotEmpty()) socket.send("public/subscribe", channels(added))
            if (removed.isNotEmpty()) socket.send("public/unsubscribe", channels(removed))
        }

    override fun close() = socket.close()

    private fun resubscribe() =
        synchronized(lock) {
            if (wanted.isNotEmpty()) socket.send("public/subscribe", channels(wanted))
        }

    private fun channels(names: Set<String>) =
        JsonObject(mapOf("channels" to JsonArray(names.map { JsonPrimitive("ticker.$it.$interval") })))

    private fun onNotification(
        channel: String,
        data: JsonElement,
    ) {
        if (channel.startsWith("ticker.")) onTicker(DeribitJson.ticker(data.jsonObject))
    }
}

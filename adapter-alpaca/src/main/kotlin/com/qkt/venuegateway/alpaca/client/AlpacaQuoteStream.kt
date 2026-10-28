package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.SocketLink
import com.qkt.venuegateway.support.SocketProtocol
import com.qkt.venuegateway.support.SubscriptionSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A market-data stream (`/v2/<feed>` for shares, `/v1beta3/crypto/us` for coin pairs) as a socket protocol: Alpaca
 * greets it with `connected`, opening logs in and waits for `authenticated` (an `error`, such as a bad key or too many
 * connections, drops the link), then subscribes the quotes of everything in [symbols]. Each quote message (`T: q`)
 * reaches [onQuote]; [onFeed] hears the feed go up and down.
 */
class AlpacaQuoteStream(
    private val key: String,
    private val secret: String,
    private val symbols: SubscriptionSet<String>,
    private val onQuote: (JsonObject) -> Unit,
    private val onFeed: (Boolean, String) -> Unit,
    private val replyMs: Long = 10_000,
) : SocketProtocol {
    @Volatile private var greeted = CompletableFuture<Unit>()

    @Volatile private var authenticated = CompletableFuture<Unit>()

    override fun opening(link: SocketLink) {
        greeted.get(replyMs, TimeUnit.MILLISECONDS)
        link.send(alpacaAuth(key, secret))
        authenticated.get(replyMs, TimeUnit.MILLISECONDS)
        subscription(symbols.all())?.let(link::send)
    }

    override fun text(frame: String) {
        val messages = AlpacaJson.parse(frame, "data stream") as? JsonArray ?: return
        for (m in messages.mapNotNull { it as? JsonObject }) {
            when (m.text("T")) {
                "success" -> if (m.text("msg") == "connected") greeted.complete(Unit) else authenticated.complete(Unit)
                "error" ->
                    authenticated.completeExceptionally(
                        IllegalStateException("alpaca data stream error ${m.text("code")} ${m.text("msg")}"),
                    )
                "q" -> onQuote(m)
            }
        }
    }

    override fun up() = onFeed(true, "alpaca data stream subscribed")

    override fun dropped(
        reason: String,
        wasUp: Boolean,
    ) {
        greeted = CompletableFuture()
        authenticated = CompletableFuture()
        if (wasUp) onFeed(false, "alpaca data stream: $reason")
    }

    /** The frame subscribing the quotes of [added], or null when there are none. */
    fun subscription(added: Collection<String>): String? = frame("subscribe", added)

    /** The frame unsubscribing the quotes of [removed], or null when there are none. */
    fun unsubscription(removed: Collection<String>): String? = frame("unsubscribe", removed)

    private fun frame(
        action: String,
        symbols: Collection<String>,
    ): String? =
        symbols.takeIf { it.isNotEmpty() }?.let { codes ->
            buildJsonObject {
                put("action", action)
                put("quotes", buildJsonArray { codes.forEach { add(JsonPrimitive(it)) } })
            }.toString()
        }
}

package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.support.JsonReads.text
import com.qkt.venuegateway.support.SocketLink
import com.qkt.venuegateway.support.SocketProtocol
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Alpaca's login frame, the same on every stream. */
internal fun alpacaAuth(
    key: String,
    secret: String,
) = buildJsonObject {
    put("action", "auth")
    put("key", key)
    put("secret", secret)
}.toString()

/**
 * The trade-update stream (`/stream`) as a socket protocol: opening logs in, waits for Alpaca to authorize it
 * (`unauthorized` drops the link), listens to `trade_updates` and waits for the confirmation; only then is the link
 * up ([onConnection]). Each update (`{stream: trade_updates, data: {...}}`) reaches [onUpdate] as its `data`.
 * Alpaca sends these frames binary; the socket reads them as text.
 */
class AlpacaTradeStream(
    private val key: String,
    private val secret: String,
    private val onUpdate: (JsonObject) -> Unit,
    private val onConnection: (Boolean, String) -> Unit,
    private val replyMs: Long = 10_000,
) : SocketProtocol {
    @Volatile private var authorized = CompletableFuture<String>()

    @Volatile private var listening = CompletableFuture<Unit>()

    override fun opening(link: SocketLink) {
        link.send(alpacaAuth(key, secret))
        val status = authorized.get(replyMs, TimeUnit.MILLISECONDS)
        check(status == "authorized") { "alpaca trade stream login $status" }
        link.send(LISTEN)
        listening.get(replyMs, TimeUnit.MILLISECONDS)
    }

    override fun text(frame: String) {
        val obj = AlpacaJson.obj(frame, "trade stream")
        val data = obj["data"] as? JsonObject ?: return
        when (obj.text("stream")) {
            "authorization" -> authorized.complete(data.text("status") ?: "without a status")
            "listening" -> listening.complete(Unit)
            "trade_updates" -> onUpdate(data)
        }
    }

    override fun up() = onConnection(true, "alpaca trade stream listening")

    override fun dropped(
        reason: String,
        wasUp: Boolean,
    ) {
        authorized = CompletableFuture()
        listening = CompletableFuture()
        if (wasUp) onConnection(false, "alpaca trade stream: $reason")
    }

    private companion object {
        const val LISTEN = """{"action":"listen","data":{"streams":["trade_updates"]}}"""
    }
}

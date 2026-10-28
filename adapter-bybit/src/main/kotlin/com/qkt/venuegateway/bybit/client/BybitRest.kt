package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.support.Hmac
import com.qkt.venuegateway.support.HttpAnswer
import com.qkt.venuegateway.support.JsonHttp
import com.qkt.venuegateway.support.JsonReads.text
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** One v5 answer: its `result`, and [timeMs], Bybit's clock when it answered (the envelope's `time`). */
data class BybitAnswer(
    val result: JsonObject,
    val timeMs: Long,
)

/**
 * Bybit v5 over HTTPS at [baseUrl]: a call answers its `result` with Bybit's time ([BybitAnswer]), a non-zero `retCode` is a
 * [BybitException], and the venue being unreachable an [IOException]. With a [key] and [secret] calls can
 * be signed (`X-BAPI-*` headers, the signature over the exact query string or body sent, valid for
 * [recvWindowMs] from [clock]'s time). Nothing is retried here: a lost answer is for the caller to resolve.
 */
class BybitRest(
    private val baseUrl: String,
    private val key: String? = null,
    private val secret: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recvWindowMs: Long = 5_000,
    timeoutMs: Long = 10_000,
    client: OkHttpClient = OkHttpClient(),
) {
    private val open = JsonHttp(baseUrl, timeoutMs, client)
    private val signed = JsonHttp(baseUrl, timeoutMs, client) { request, _, _, payload -> sign(request, payload) }

    /**
     * GETs [path] with [params], in their order; signed when [signed]. Values are sent as they are (Bybit's
     * symbols, numbers and page cursors need no encoding; a cursor comes back already encoded).
     */
    fun get(
        path: String,
        params: List<Pair<String, String>>,
        signed: Boolean = false,
    ): BybitAnswer = answer(path, (if (signed) this.signed else open).get(path, params))

    /** POSTs [body] to [path], signed. */
    fun post(
        path: String,
        body: JsonObject,
    ): BybitAnswer = answer(path, signed.post(path, body))

    private fun sign(
        request: Request.Builder,
        payload: String,
    ) {
        val secret = secret ?: error("bybit $baseUrl was built without an API key")
        val timestamp = clock().toString()
        val window = recvWindowMs.toString()
        request
            .header("X-BAPI-API-KEY", key!!)
            .header("X-BAPI-TIMESTAMP", timestamp)
            .header("X-BAPI-RECV-WINDOW", window)
            .header("X-BAPI-SIGN", Hmac.sha256Hex(secret, timestamp + key + window + payload))
    }

    private fun answer(
        path: String,
        response: HttpAnswer,
    ): BybitAnswer {
        if (response.status == 403 || response.unavailable) {
            throw BybitException(response.status, "HTTP ${response.status} on $path")
        }
        val tree =
            runCatching { response.json().jsonObject }.getOrElse {
                throw IOException("bybit answered HTTP ${response.status} on $path without JSON")
            }
        val code =
            (tree["retCode"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: throw IOException("bybit answered $path without retCode")
        if (code != 0) throw BybitException(code, tree.text("retMsg") ?: "")
        val time = (tree["time"] as? JsonPrimitive)?.content?.toLongOrNull() ?: clock()
        return BybitAnswer(tree["result"] as? JsonObject ?: JsonObject(emptyMap()), time)
    }

    override fun toString() = "BybitRest($baseUrl)"
}

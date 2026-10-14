package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.client.BybitJson.text
import java.io.IOException
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    secret: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recvWindowMs: Long = 5_000,
    timeoutMs: Long = 10_000,
) {
    private val http = OkHttpClient.Builder().callTimeout(Duration.ofMillis(timeoutMs)).build()
    private val signer = secret?.let(::BybitSigner)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * GETs [path] with [params], in their order; signed when [signed]. Values are sent as they are (Bybit's
     * symbols, numbers and page cursors need no encoding; a cursor comes back already encoded).
     */
    fun get(
        path: String,
        params: List<Pair<String, String>>,
        signed: Boolean = false,
    ): BybitAnswer {
        val query = params.joinToString("&") { (k, v) -> "$k=$v" }
        val url = if (query.isEmpty()) "$baseUrl$path" else "$baseUrl$path?$query"
        val request = Request.Builder().url(url.toHttpUrl()).get()
        if (signed) sign(request, query)
        return execute(request.build())
    }

    /** POSTs [body] to [path], signed. */
    fun post(
        path: String,
        body: JsonObject,
    ): BybitAnswer {
        val text = body.toString()
        val request = Request.Builder().url("$baseUrl$path").post(text.toRequestBody(JSON_TYPE))
        sign(request, text)
        return execute(request.build())
    }

    private fun sign(
        request: Request.Builder,
        payload: String,
    ) {
        val signer = signer ?: error("bybit $baseUrl was built without an API key")
        val timestamp = clock().toString()
        val window = recvWindowMs.toString()
        request
            .header("X-BAPI-API-KEY", key!!)
            .header("X-BAPI-TIMESTAMP", timestamp)
            .header("X-BAPI-RECV-WINDOW", window)
            .header("X-BAPI-SIGN", signer.sign(timestamp + key + window + payload))
    }

    private fun execute(request: Request): BybitAnswer =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 403 || response.code == 429 || response.code >= 500) {
                throw BybitException(response.code, "HTTP ${response.code} on ${request.url.encodedPath}")
            }
            val tree =
                runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                    throw IOException("bybit answered HTTP ${response.code} on ${request.url.encodedPath} without JSON")
                }
            val code =
                (tree["retCode"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw IOException("bybit answered ${request.url.encodedPath} without retCode")
            if (code != 0) throw BybitException(code, tree.text("retMsg") ?: "")
            val time = (tree["time"] as? JsonPrimitive)?.content?.toLongOrNull() ?: clock()
            BybitAnswer(tree["result"] as? JsonObject ?: JsonObject(emptyMap()), time)
        }

    override fun toString() = "BybitRest($baseUrl)"

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}

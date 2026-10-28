package com.qkt.venuegateway.alpaca.client

import com.qkt.venuegateway.support.HttpAnswer
import com.qkt.venuegateway.support.JsonHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * HTTP as the Alpaca clients need it: JSON text in and out, against one host. A 2xx answer is its body (empty for a
 * 204); any other status throws [AlpacaHttpException] with the body Alpaca sent, whose `code` and `message` the
 * mapping reads; the host being unreachable throws `IOException`. Nothing is retried.
 */
interface AlpacaHttp {
    fun get(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): String

    fun send(
        method: String,
        path: String,
        body: String,
    ): String

    fun delete(path: String): String
}

/** Alpaca answered [status]; [body] is its answer, usually `{"code": ..., "message": ...}`, its message the exception's. */
class AlpacaHttpException(
    val status: Int,
    val body: String,
) : RuntimeException(messageOf(status, body))

private fun messageOf(
    status: Int,
    body: String,
): String =
    runCatching { (Json.parseToJsonElement(body) as JsonObject)["message"]?.jsonPrimitive?.content }.getOrNull()
        ?: "HTTP $status"

/** [AlpacaHttp] over the shared [JsonHttp] at [baseUrl] on the host's [client], with Alpaca's `APCA-API-KEY-ID` / `APCA-API-SECRET-KEY` headers. */
class AlpacaJsonHttp(
    baseUrl: String,
    key: String,
    secret: String,
    client: OkHttpClient,
    timeoutMs: Long = 15_000,
) : AlpacaHttp {
    private val http =
        JsonHttp(baseUrl, timeoutMs, client, encodeQuery = true) { request, _, _, _ ->
            request.header("APCA-API-KEY-ID", key).header("APCA-API-SECRET-KEY", secret)
        }

    /** Query values go encoded (`encodeQuery`): Alpaca's times carry `:` and `+`, its page tokens `=`. */
    override fun get(
        path: String,
        params: List<Pair<String, String>>,
    ) = text(http.get(path, params))

    override fun send(
        method: String,
        path: String,
        body: String,
    ): String {
        val json = Json.parseToJsonElement(body)
        return text(if (method == "PATCH") http.patch(path, json) else http.post(path, json))
    }

    override fun delete(path: String) = text(http.delete(path))

    private fun text(answer: HttpAnswer): String {
        if (answer.status !in 200..299) throw AlpacaHttpException(answer.status, answer.text)
        return answer.text
    }

    override fun toString() = "AlpacaJsonHttp($http)"
}

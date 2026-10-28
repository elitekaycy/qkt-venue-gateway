package com.qkt.venuegateway.support

import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One answer: its HTTP [status] and [text]; [json] parses it as the object or array every venue answers, anything
 * else (an HTML error page, a bare word, nothing) being an [IOException].
 */
class HttpAnswer(
    val status: Int,
    val text: String,
    private val what: String,
) {
    /** 429 or 5xx: the venue could not answer now; anything else is the venue's answer to this request. */
    val unavailable: Boolean get() = status == 429 || status >= 500

    fun json(): JsonElement =
        runCatching { Json.parseToJsonElement(text) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
            ?: throw IOException("$what answered HTTP $status without JSON")
}

/**
 * JSON over HTTPS to [baseUrl]. Every call returns the venue's [HttpAnswer] whatever its status, so the venue's own
 * envelope (an error code in a 200, a message in a 4xx) is read in its mapping, not guessed here; only the venue
 * being unreachable, or no answer within [timeoutMs], is an [IOException]. [sign] adds a venue's auth headers to
 * each request from the exact payload sent (the query string, or the body). With [encodeQuery] each query value is
 * URL-encoded (a venue whose times carry `:` and `+`, or whose page tokens carry `=`); without it values are sent as
 * given, for a venue that signs the exact query string. Nothing is retried: a lost answer is the caller's to resolve,
 * because a retried order is a second order.
 */
class JsonHttp(
    private val baseUrl: String,
    timeoutMs: Long = 10_000,
    http: OkHttpClient = OkHttpClient(),
    private val encodeQuery: Boolean = false,
    private val sign: (
        request: Request.Builder,
        method: String,
        path: String,
        payload: String,
    ) -> Unit = { _, _, _, _ -> },
) {
    private val http = http.newBuilder().callTimeout(Duration.ofMillis(timeoutMs)).build()

    /** GETs [path] with [params] in their order, encoded when [encodeQuery] says so. */
    fun get(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): HttpAnswer = call("GET", path, params, null)

    fun delete(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): HttpAnswer = call("DELETE", path, params, null)

    fun post(
        path: String,
        body: JsonElement,
    ): HttpAnswer = call("POST", path, emptyList(), body.toString())

    fun patch(
        path: String,
        body: JsonElement,
    ): HttpAnswer = call("PATCH", path, emptyList(), body.toString())

    private fun call(
        method: String,
        path: String,
        params: List<Pair<String, String>>,
        body: String?,
    ): HttpAnswer {
        val query =
            params.joinToString(
                "&",
            ) { (k, v) -> "$k=${if (encodeQuery) URLEncoder.encode(v, Charsets.UTF_8) else v}" }
        val url = if (query.isEmpty()) "$baseUrl$path" else "$baseUrl$path?$query"
        val request = Request.Builder().url(url.toHttpUrl()).method(method, body?.toRequestBody(JSON_TYPE))
        sign(request, method, path, body ?: query)
        return http.newCall(request.build()).execute().use { response ->
            HttpAnswer(response.code, response.body?.string().orEmpty(), "$method $path")
        }
    }

    override fun toString() = "JsonHttp($baseUrl)"

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}

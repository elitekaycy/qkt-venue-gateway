package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.support.HttpAnswer
import com.qkt.venuegateway.support.JsonHttp
import com.qkt.venuegateway.support.JsonReads.text
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Kalshi trade API v2 over HTTPS at [baseUrl] (`https://…/trade-api/v2`). A 2xx answer is its JSON object; any other
 * is a [KalshiException] carrying Kalshi's `error.code` and `error.message` (a 404 is `not_found`) and the HTTP
 * status; the venue being unreachable is an [IOException]. With [auth] every call is signed ([KalshiAuth]) at
 * [clock]'s time; without it only the public market can be read. Query values are URL-encoded (Kalshi signs the
 * path without its query, and its page cursors are opaque). Nothing is retried: a lost answer is the caller's.
 */
class KalshiRest(
    private val baseUrl: String,
    private val auth: KalshiAuth? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    timeoutMs: Long = 10_000,
    client: OkHttpClient = OkHttpClient(),
) {
    private val prefix = baseUrl.toHttpUrl().encodedPath.removeSuffix("/")
    private val http =
        JsonHttp(baseUrl, timeoutMs, client, encodeQuery = true) { request, method, path, _ ->
            auth?.headers(clock(), method, prefix + path)?.forEach { (k, v) -> request.header(k, v) }
        }

    fun get(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): JsonObject = answer("GET $path", http.get(path, params))

    fun post(
        path: String,
        body: JsonObject,
    ): JsonObject = answer("POST $path", http.post(path, body))

    fun delete(path: String): JsonObject = answer("DELETE $path", http.delete(path))

    /** The answer of [call], or null when Kalshi answers 404 (`not_found`): the thing asked for does not exist. */
    fun <T> orNull(call: () -> T): T? =
        try {
            call()
        } catch (e: KalshiException) {
            if (e.status == NOT_FOUND) null else throw e
        }

    private fun answer(
        what: String,
        response: HttpAnswer,
    ): JsonObject {
        if (response.status in 200..299) {
            return response.json() as? JsonObject ?: throw IOException("kalshi answered $what without an object")
        }
        val error = runCatching { (response.json() as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
        throw KalshiException(
            response.status,
            error?.text("code") ?: "http_${response.status}",
            listOfNotNull(error?.text("message"), error?.text("details")).joinToString(": ").ifEmpty { what },
        )
    }

    override fun toString() = "KalshiRest($baseUrl)"

    private companion object {
        const val NOT_FOUND = 404
    }
}

/** Kalshi answered a request without doing it: HTTP [status], Kalshi's own error [code] and its message. */
class KalshiException(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException("kalshi $status $code: $message")

package com.qkt.venuegateway.bybit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The first record of a recorded answer's `result.list` (or its `data`), for a test to alter one field of. */
internal fun firstRecord(fixture: String): JsonObject {
    val root = Json.parseToJsonElement(Fixtures.text(fixture)).jsonObject
    val list = root["result"]?.jsonObject?.get("list") ?: root["data"]!!
    return list.jsonArray.first().jsonObject
}

/** [this] with each field set to its text, or removed when the text is null: a recorded record gone odd. */
internal fun JsonObject.with(vararg edits: Pair<String, String?>): JsonObject {
    val fields = toMutableMap()
    edits.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = JsonPrimitive(value) }
    return JsonObject(fields)
}

/** [this] with [key] sent as JSON null. */
internal fun JsonObject.nulled(key: String): JsonObject = JsonObject(toMutableMap().apply { put(key, JsonNull) })

/** A server answering each request with the text [route] gives for its path and query (HTTP 404 for none). */
internal class Canned(
    private val route: (path: String, query: Map<String, String>) -> String?,
) : AutoCloseable {
    private val server =
        mockwebserver3.MockWebServer().apply {
            dispatcher =
                object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                        val url = request.url
                        val query = url.queryParameterNames.associateWith { url.queryParameter(it)!! }
                        val body = route(url.encodedPath, query) ?: return mockwebserver3.MockResponse(code = 404)
                        return mockwebserver3.MockResponse(body = body)
                    }
                }
            start()
        }

    val url: String get() = server.url("/").toString().removeSuffix("/")

    override fun close() = server.close()
}

/** The recorded answer [fixture] with its `result.list` replaced by [records]. */
internal fun answerWith(
    fixture: String,
    records: List<JsonObject>,
): String {
    val root = Json.parseToJsonElement(Fixtures.text(fixture)).jsonObject
    val result =
        root["result"]!!.jsonObject.toMutableMap().apply {
            put("list", kotlinx.serialization.json.JsonArray(records))
        }
    return JsonObject(root.toMutableMap().apply { put("result", JsonObject(result)) }).toString()
}

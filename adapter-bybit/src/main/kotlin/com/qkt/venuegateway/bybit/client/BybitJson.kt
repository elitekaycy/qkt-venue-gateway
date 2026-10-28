package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.support.JsonReads.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The shapes of Bybit's JSON that are Bybit's own: a v5 `result`'s `list`, its arrays of arrays, and its page cursor.
 * Fields are read with [com.qkt.venuegateway.support.JsonReads]: Bybit sends every decimal as a string and an
 * absent value as `""`, which reads as absent there.
 */
internal object BybitJson {
    /** The `list` of a v5 `result`, each item an object. */
    fun JsonObject.items(key: String = "list"): List<JsonObject> =
        (this[key] as? JsonArray ?: error("bybit field $key missing")).map { it.jsonObject }

    /** The rows of a v5 array of arrays (klines, book levels), each as its strings. */
    fun rows(element: JsonElement?): List<List<String>> =
        (element as? JsonArray ?: error("bybit rows missing")).map { row ->
            row.jsonArray.map { (it as JsonPrimitive).content }
        }

    /** A page's `nextPageCursor`, null on the last page (Bybit sends `""` or `null` there). */
    fun JsonObject.cursor(): String? = text("nextPageCursor")?.takeIf { it != "null" }
}

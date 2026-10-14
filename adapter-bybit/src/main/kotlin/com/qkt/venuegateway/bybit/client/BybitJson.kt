package com.qkt.venuegateway.bybit.client

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Reading Bybit's JSON exactly. Bybit sends every decimal as a string (`"87717.60"`) and an absent value as
 * an empty string, so a decimal is read from its text into a [BigDecimal], never through a double, and `""`
 * reads as absent. A field the caller needs and Bybit left out fails naming it.
 */
internal object BybitJson {
    fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    fun JsonObject.need(key: String): String = text(key) ?: error("bybit field $key missing in $this")

    /** The decimal at [key], null when absent or empty. */
    fun JsonObject.dec(key: String): BigDecimal? = text(key)?.let(::BigDecimal)

    fun JsonObject.req(key: String): BigDecimal = BigDecimal(need(key))

    fun JsonObject.long(key: String): Long = need(key).toLong()

    fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: error("bybit field $key missing in $this")

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

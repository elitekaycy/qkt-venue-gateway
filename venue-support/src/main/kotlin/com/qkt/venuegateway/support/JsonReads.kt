package com.qkt.venuegateway.support

import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading a venue's JSON exactly. A decimal is read from its literal text, quoted (`"87717.60"`) or not
 * (`83940.0`, `1e-06`), into a [BigDecimal], never through a double. JSON `null` and an empty string read as absent,
 * as venues send both for "no value". A field the caller needs and the venue left out fails naming it and the keys
 * the object did carry, never its values (they may be account data).
 */
object JsonReads {
    /** The text of [key], null when absent, `null` or empty. */
    fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotEmpty() }

    fun JsonObject.need(key: String): String = text(key) ?: missing(key)

    /** The decimal at [key], null when absent. */
    fun JsonObject.decimal(key: String): BigDecimal? = text(key)?.let(::BigDecimal)

    fun JsonObject.needDecimal(key: String): BigDecimal = decimal(key) ?: missing(key)

    /** A whole number at [key], written as an integer or as a decimal with no fraction (`1700000000000.0`). */
    fun JsonObject.long(key: String): Long = BigDecimal(need(key)).longValueExact()

    fun JsonObject.longOrNull(key: String): Long? = text(key)?.let { BigDecimal(it).longValueExact() }

    fun JsonObject.bool(key: String): Boolean? = text(key)?.toBooleanStrict()

    /** An RFC 3339 time at [key] (`2026-10-28T00:44:03.565024076Z`, offsets included) in epoch milliseconds. */
    fun JsonObject.instantMs(key: String): Long? =
        text(key)?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }

    fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: missing(key)

    fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

    /** The objects of the array at [key]; an absent or `null` array is empty, anything else not an array fails. */
    fun JsonObject.objects(key: String): List<JsonObject> =
        when (val value = this[key]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.map { it as? JsonObject ?: error("field $key holds a non-object") }
            else -> error("field $key is not an array")
        }

    /** [element] as an array of objects, failing naming [what] otherwise. */
    fun objects(
        element: JsonElement,
        what: String,
    ): List<JsonObject> =
        (element as? JsonArray ?: error("$what is not an array")).map {
            it as? JsonObject
                ?: error("$what holds a non-object")
        }

    private fun JsonObject.missing(key: String): Nothing = error("field $key missing (has ${keys.sorted()})")
}

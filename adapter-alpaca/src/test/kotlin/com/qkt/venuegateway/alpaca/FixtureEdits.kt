package com.qkt.venuegateway.alpaca

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [this] with each field set to its text, or removed when the text is null: a recorded record gone odd. */
internal fun JsonObject.with(vararg edits: Pair<String, String?>): JsonObject {
    val fields = toMutableMap()
    edits.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = JsonPrimitive(value) }
    return JsonObject(fields)
}

/** [this] with [key] sent as JSON null. */
internal fun JsonObject.nulled(key: String): JsonObject = JsonObject(toMutableMap().apply { put(key, JsonNull) })

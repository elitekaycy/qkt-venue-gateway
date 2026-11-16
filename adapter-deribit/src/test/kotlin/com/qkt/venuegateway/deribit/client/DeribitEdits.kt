package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The `result` of recorded answer [name] under `fixtures/` (wrapped as a private recording or bare). */
internal fun recordedResult(name: String): JsonElement {
    val root = Json.parseToJsonElement(DeribitEdits::class.java.getResource("/fixtures/$name")!!.readText()).jsonObject
    return (root["response"]?.jsonObject ?: root)["result"]!!
}

/** The first object of recorded answer [name]'s result (the result itself when it is an object). */
internal fun recordedObject(name: String): JsonObject =
    recordedResult(name).let { if (it is JsonObject) it else it.jsonArray.first().jsonObject }

/** The numbers and words a recorded record is altered with. */
internal object DeribitEdits {
    fun num(value: String): JsonElement = JsonPrimitive(BigDecimal(value))

    fun text(value: String): JsonElement = JsonPrimitive(value)
}

/** [this] with each field set, or removed when its value is null: a recorded record gone odd. */
internal fun JsonObject.set(vararg edits: Pair<String, JsonElement?>): JsonObject {
    val fields = toMutableMap()
    edits.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = value }
    return JsonObject(fields)
}

internal fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(filterKeys { it !in keys })

internal val NULL: JsonElement = JsonNull

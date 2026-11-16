package com.qkt.vgp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/** The gateway's own JSON settings (the host's `wireJson`): defaults written, absent values left out. */
internal val gatewayJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * Checks a wire object keeps its shape: [base] equals [same] and nothing else, each of [variants] ([base] with one
 * field changed) is a different value, and every one reads back from the JSON it writes, under the library's
 * defaults (defaults left out) and the gateway's settings (defaults written). Returns [base] as the gateway writes it.
 */
internal inline fun <reified T : Any> wireShape(
    base: T,
    same: T,
    vararg variants: T,
): JsonObject {
    val serializer = serializer<T>()
    val all = listOf(base) + variants
    assertThat(base).isEqualTo(base).isNotEqualTo(Any())
    assertThat(base).isEqualTo(same).hasSameHashCodeAs(same)
    assertThat(all.toSet()).describedAs("every variant differs").hasSize(all.size)
    all.forEach { value ->
        listOf(Json, gatewayJson).forEach { json ->
            assertThat(json.decodeFromString(serializer, json.encodeToString(serializer, value))).isEqualTo(value)
        }
    }
    return gatewayJson.parseToJsonElement(gatewayJson.encodeToString(serializer, base)).jsonObject
}

/** A body missing a required field is refused, never filled in with a made-up value. */
internal inline fun <reified T : Any> refusesEmpty() {
    assertThatThrownBy { Json.decodeFromString(serializer<T>(), "{}") }.isInstanceOf(SerializationException::class.java)
}

/** [value] as the gateway writes it. */
internal inline fun <reified T : Any> written(value: T): String = gatewayJson.encodeToString(serializer<T>(), value)

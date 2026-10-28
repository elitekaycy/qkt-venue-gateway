package com.qkt.venuegateway.alpaca.client

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Alpaca's answers parsed; fields and RFC 3339 times are read with the shared readers (`JsonReads`). */
object AlpacaJson {
    /** [text] parsed, or an [IOException] naming [what] answered without JSON. */
    fun parse(
        text: String,
        what: String,
    ): JsonElement =
        runCatching {
            Json.parseToJsonElement(text)
        }.getOrElse { throw IOException("alpaca $what answered without JSON") }

    fun obj(
        text: String,
        what: String,
    ): JsonObject = parse(text, what) as? JsonObject ?: throw IOException("alpaca $what answered no object")

    fun array(
        text: String,
        what: String,
    ): JsonArray = parse(text, what) as? JsonArray ?: throw IOException("alpaca $what answered no list")
}

package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Responses recorded from Alpaca's paper environment (account ids removed), as text or JSON. */
object Fixtures {
    fun text(name: String): String =
        requireNotNull(Fixtures::class.java.getResource("/fixtures/$name")) { "no fixture $name" }.readText()

    fun obj(name: String): JsonObject = AlpacaJson.obj(text(name), name)

    fun array(name: String): List<JsonObject> = AlpacaJson.array(text(name), name).map { it as JsonObject }

    /** The JSON frames of a recorded socket session, in arrival order. */
    fun frames(name: String): List<String> = array(name).map { (it["text"] as JsonPrimitive).content }

    /** The trade updates (`data`) of a recorded trade-stream session. */
    fun updates(name: String): List<JsonObject> =
        frames(name)
            .map { AlpacaJson.obj(it, name) }
            .filter {
                (it["stream"] as JsonPrimitive).content ==
                    "trade_updates"
            }.map { it["data"]!!.jsonObject }

    /** The quote messages of a recorded data-stream session. */
    fun quotes(name: String): List<JsonObject> =
        frames(name)
            .flatMap { AlpacaJson.parse(it, name) as JsonArray }
            .map { it.jsonObject }
            .filter { (it["T"] as JsonPrimitive).content == "q" }
}

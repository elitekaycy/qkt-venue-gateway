package com.qkt.venuegateway.deribit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What one frame from Deribit is. */
internal sealed interface DeribitFrame {
    /** The answer to request [id]. */
    data class Answer(
        val id: Long,
        val result: JsonElement,
    ) : DeribitFrame

    /** Request [id] was refused with [error]. */
    data class Refusal(
        val id: Long,
        val error: DeribitException,
    ) : DeribitFrame

    /** A subscription notification on [channel]. */
    data class Notification(
        val channel: String,
        val data: JsonElement,
    ) : DeribitFrame

    /** Deribit asks for a `public/test` to keep the link. */
    data object TestRequest : DeribitFrame

    /** Anything else (a plain heartbeat). */
    data object Other : DeribitFrame
}

/** Deribit's JSON-RPC 2.0 framing, both ways. */
internal object DeribitFrames {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(
        id: Long,
        method: String,
        params: JsonObject,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    fun parse(text: String): DeribitFrame {
        val message = json.parseToJsonElement(text) as? JsonObject ?: return DeribitFrame.Other
        val id = (message["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id != null) {
            val error = message["error"] as? JsonObject
            return if (error !=
                null
            ) {
                DeribitFrame.Refusal(id, refusal(error))
            } else {
                DeribitFrame.Answer(
                    id,
                    message["result"] ?: JsonNull,
                )
            }
        }
        val params = message["params"] as? JsonObject ?: return DeribitFrame.Other
        return when (message["method"]?.jsonPrimitive?.content) {
            "subscription" -> DeribitFrame.Notification(params["channel"]!!.jsonPrimitive.content, params["data"]!!)
            "heartbeat" ->
                if (params["type"]?.jsonPrimitive?.content ==
                    "test_request"
                ) {
                    DeribitFrame.TestRequest
                } else {
                    DeribitFrame.Other
                }
            else -> DeribitFrame.Other
        }
    }

    private fun refusal(error: JsonObject) =
        DeribitException(
            error["code"]?.jsonPrimitive?.int ?: 0,
            error["message"]?.jsonPrimitive?.content ?: "unknown",
            (error["data"] as? JsonObject)?.get("reason")?.jsonPrimitive?.content,
        )
}

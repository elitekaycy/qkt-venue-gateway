package com.qkt.venuegateway.deribit.client

import com.qkt.venuegateway.deribit.client.DeribitEdits.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeribitFramesArmsTest {
    private fun response(name: String): JsonObject =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/private/$name")!!.readText())
            .jsonObject["response"]!!
            .jsonObject

    private val refused = response("cancel-market-remainder-again.json")
    private val answered = response("cancel-by-label-one.json")
    private val notifications =
        Json
            .parseToJsonElement(
                javaClass.getResource("/fixtures/private/ws-user-notifications.json")!!.readText(),
            ).jsonObject

    @Test
    fun `an error naming no code, no message or no reason is a refusal with the words it has`() {
        val error = refused["error"]!!.jsonObject

        val bare = DeribitFrames.parse(JsonObject(refused + ("error" to JsonObject(emptyMap()))).toString())
        val reasoned =
            DeribitFrames.parse(
                JsonObject(
                    refused + ("error" to error.set("data" to JsonObject(mapOf("reason" to text("because"))))),
                ).toString(),
            )

        assertThat((bare as DeribitFrame.Refusal).error.code).isZero
        assertThat(bare.error.message).contains("unknown")
        assertThat(bare.error.reason).isNull()
        assertThat((reasoned as DeribitFrame.Refusal).error.reason).isEqualTo("because")
        assertThat(reasoned.error.code).isEqualTo(11044)
    }

    @Test
    fun `an answer naming no result is an answer of null`() {
        val frame = DeribitFrames.parse(answered.without("result").toString())

        assertThat((frame as DeribitFrame.Answer).result.toString()).isEqualTo("null")
    }

    @Test
    fun `an id that is no number leaves the frame to its method, and a frame without params is nothing`() {
        val params = refused.set("id" to text("abc"))

        assertThat(DeribitFrames.parse(params.toString())).isEqualTo(DeribitFrame.Other)
        assertThat(DeribitFrames.parse(answered.without("id").toString())).isEqualTo(DeribitFrame.Other)
    }

    @Test
    fun `a notification of another method, or a heartbeat with no type, is nothing to act on`() {
        val first =
            notifications["notifications"]!!
                .let { (it as kotlinx.serialization.json.JsonArray).first() }
                .jsonObject
        val other = first.set("method" to text("something_else"))
        val heartbeat = JsonObject(mapOf("method" to text("heartbeat"), "params" to JsonObject(emptyMap())))

        assertThat(DeribitFrames.parse(first.toString())).isInstanceOf(DeribitFrame.Notification::class.java)
        assertThat(DeribitFrames.parse(other.toString())).isEqualTo(DeribitFrame.Other)
        assertThat(DeribitFrames.parse(heartbeat.toString())).isEqualTo(DeribitFrame.Other)
        assertThat(DeribitFrames.parse(first.without("method").toString())).isEqualTo(DeribitFrame.Other)
    }
}

package com.qkt.venuegateway.deribit.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** What each frame Deribit sends is, read from the testnet recordings. */
class DeribitFramesTest {
    private fun recorded(name: String) = javaClass.getResource("/fixtures/private/$name")!!.readText()

    @Test
    fun `of the two recorded heartbeats only the test request asks for an answer`() {
        val heartbeats = Json.parseToJsonElement(recorded("ws-heartbeat.json")).jsonObject
        val (plain, test) = heartbeats["notifications"]!!.jsonArray

        assertThat(DeribitFrames.parse(plain.toString())).isEqualTo(DeribitFrame.Other)
        assertThat(DeribitFrames.parse(test.toString())).isEqualTo(DeribitFrame.TestRequest)
    }

    @Test
    fun `a frame without an id calling neither a subscription nor a heartbeat is nothing to act on`() {
        val request = recorded("account-summary.json")

        assertThat(DeribitFrames.parse(request)).isEqualTo(DeribitFrame.Other)
    }

    @Test
    fun `a frame that is not a JSON object is nothing to act on`() {
        val result = Json.parseToJsonElement(recorded("open-orders-with-stops.json")).jsonObject["response"]!!
        val orders = result.jsonObject["result"]!!.jsonArray

        assertThat(DeribitFrames.parse(orders.toString())).isEqualTo(DeribitFrame.Other)
    }

    @Test
    fun `an answer carrying its id is an answer, and one carrying an error is a refusal with the error's code`() {
        val answered = Json.parseToJsonElement(recorded("cancel-by-label-one.json")).jsonObject["response"]!!
        val refused = Json.parseToJsonElement(recorded("cancel-market-remainder-again.json")).jsonObject["response"]!!

        val answer = DeribitFrames.parse(answered.toString())
        val refusal = DeribitFrames.parse(refused.toString())

        assertThat(answer).isInstanceOf(DeribitFrame.Answer::class.java)
        assertThat((answer as DeribitFrame.Answer).result.toString()).isEqualTo("1")
        assertThat((refusal as DeribitFrame.Refusal).error.code).isEqualTo(11044)
    }
}

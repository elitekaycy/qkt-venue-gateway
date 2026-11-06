package com.qkt.venuegateway.deribit.client

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The private link's own upkeep: Deribit's heartbeat checks and refusals, from the testnet recordings. */
class DeribitPrivateLinkTest {
    private val venue = RecordedDeribitSocket()
    private val client = venue.connected()

    @AfterEach
    fun stop() {
        client.close()
        venue.close()
    }

    @Test
    fun `a heartbeat test request is answered with a public test, and a plain heartbeat with nothing`() {
        val heartbeats = venue.recorded("ws-heartbeat.json")["notifications"]!!.jsonArray

        heartbeats.forEach { venue.sockets.single().send(it.toString()) }

        assertThat(venue.next("public/test")["params"]!!.jsonObject).isEmpty()
        assertThat(venue.sent.poll(500, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun `a refused cancel fails with Deribit's code, and a refusal nobody waits for leaves the link serving`() {
        venue.answers["private/cancel"] = "cancel-market-remainder-again.json"
        venue.answers["private/get_account_summary"] = "account-summary.json"
        val id = venue.recorded("cancel-market-remainder-again.json")["params"]!!.jsonObject["order_id"]!!
        val orderId = id.jsonPrimitive.content

        assertThatThrownBy { client.cancel(orderId) }
            .isInstanceOfSatisfying(DeribitException::class.java) {
                assertThat(it.code).isEqualTo(11044)
                assertThat(it.unavailable).isFalse
            }
        client.cancelSoon(orderId)
        assertThat(venue.next("private/cancel").toString()).contains(orderId)

        assertThat(client.account("USDC").currency).isEqualTo("USDC")
        assertThat(venue.connections).containsExactly(true)
    }

    @Test
    fun `a code Deribit documents as not serving marks the refusal unavailable`() {
        assertThat(DeribitException(13028, "temporarily_unavailable").unavailable).isTrue
        assertThat(DeribitException(10028, "too_many_requests").unavailable).isTrue
    }
}

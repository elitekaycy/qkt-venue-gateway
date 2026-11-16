package com.qkt.venuegateway.deribit.client

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DeribitPrivatePushesTest {
    private val venue = RecordedDeribitSocket()
    private val orders = CopyOnWriteArrayList<DeribitOrder>()
    private val trades = CopyOnWriteArrayList<DeribitTrade>()
    private val client = venue.connected({ orders += it }, { trades += it })

    @AfterEach
    fun stop() {
        client.close()
        venue.close()
    }

    private val notifications: List<JsonObject> =
        venue.recorded("ws-user-notifications.json")["notifications"]!!.jsonArray.map { it.jsonObject }

    private fun until(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private fun asBatch(frame: JsonObject): JsonObject {
        val params = frame["params"]!!.jsonObject
        return JsonObject(frame + ("params" to JsonObject(params + ("data" to JsonArray(listOf(params["data"]!!))))))
    }

    @Test
    fun `an order or trade pushed alone, or in a batch, reaches its handler, and another channel reaches none`() {
        val order = notifications.first { it["params"]!!.jsonObject["channel"].toString().contains("orders") }
        val socket = venue.sockets.single()

        socket.send(order.toString())
        socket.send(asBatch(order).toString())
        socket.send(
            Json.encodeToString(
                JsonObject.serializer(),
                JsonObject(
                    order +
                        (
                            "params" to
                                JsonObject(
                                    order["params"]!!.jsonObject +
                                        ("channel" to kotlinx.serialization.json.JsonPrimitive("user.portfolio.USDC")),
                                )
                        ),
                ),
            ),
        )
        until { orders.size == 2 }
        Thread.sleep(200)

        assertThat(orders).hasSize(2)
        assertThat(orders.map { it.orderId }.distinct()).hasSize(1)
        socket.send(
            notifications.first { it["params"]!!.jsonObject["channel"].toString().contains("trades") }.toString(),
        )
        until { trades.isNotEmpty() }
        venue.answers["private/get_account_summary"] = "account-summary.json"
        assertThat(client.account("USDC").currency).isEqualTo("USDC")
    }
}

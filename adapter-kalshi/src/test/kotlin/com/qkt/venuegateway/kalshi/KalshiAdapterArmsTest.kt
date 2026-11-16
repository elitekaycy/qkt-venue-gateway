package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.FILLED
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.SETTLED
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** The adapter and its listing over the recorded venue, one recorded answer altered where a case needs it. */
class KalshiAdapterArmsTest {
    private val venue = KalshiVenue()

    @AfterEach
    fun stop() = venue.close()

    private fun connected(shard: Int = 0) =
        venue.adapter(shard).also {
            it.connect(venue.listener)
            venue.await { venue.links.contains(true) && venue.server.sent.size >= 2 }
        }

    @Test
    fun `a pushed fill naming no client id takes the label learnt for its order, or its order id when none is`() {
        connected().use { adapter ->
            val fill =
                JsonObject(
                    Fixtures.json("socket/fill.json").let { f ->
                        f +
                            ("msg" to JsonObject(f["msg"]!!.jsonObject - "client_order_id"))
                    },
                )

            venue.server.push(fill.toString())
            venue.await { venue.fills.isNotEmpty() }
            adapter.place(
                NewOrder(
                    "qkt-probe-fill",
                    CURRY,
                    Side.BUY,
                    OrderType.LIMIT,
                    BigDecimal.ONE,
                    BigDecimal("0.01"),
                    null,
                    TimeInForce.GTC,
                ),
            )
            venue.server.push(fill.toString())
            venue.await { venue.fills.size == 2 }

            assertThat(venue.fills.map { it.clientOrderId }).containsExactly(FILLED, "qkt-probe-fill")
        }
    }

    @Test
    fun `an account on a shard kalshi lists no balance for holds no cash`() {
        val balance = Fixtures.json("private/balance.json")
        val shardZero = balance["balance_breakdown"]!!.jsonArray.take(1)
        venue.overrides["GET /portfolio/balance"] =
            JsonObject(balance + ("balance_breakdown" to JsonArray(shardZero))).toString()

        venue.adapter(shard = 1).use { adapter ->
            assertThat(adapter.account().balance).isEqualByComparingTo("0")
        }
    }

    @Test
    fun `positions are this shard's and those naming no shard, never another shard's`() {
        val positions = Fixtures.json("private/positions.json")
        val rows = positions["market_positions"]!!.jsonArray.map { it.jsonObject }
        val unsharded = listOf(JsonObject(rows.first() - "exchange_index")) + rows.drop(1)
        venue.overrides["GET /portfolio/positions"] =
            JsonObject(positions + ("market_positions" to JsonArray(unsharded))).toString()

        venue.adapter(shard = 1).use { adapter ->
            val held = adapter.positions().rows.map { it.symbol }

            assertThat(held).containsExactly(rows.first()["ticker"].toString().trim('"'))
        }
    }

    @Test
    fun `quotes subscribed before a listener is connected are read but pushed to no one`() {
        venue.adapter().use { adapter ->
            adapter.subscribeQuotes(setOf(CURRY))

            assertThat(venue.server.requests.count { it.url.encodedPath.endsWith("/markets") }).isEqualTo(1)
            assertThat(venue.quotes).isEmpty()
        }
    }

    @Test
    fun `a market whose event kalshi no longer answers is no instrument`() {
        venue.overrides["GET /events/KXHIGHNY-26NOV04"] = "!404 private/error-not-found.json"

        venue.adapter().use { adapter -> assertThat(adapter.instrument(SETTLED)).isNull() }
    }

    @Test
    fun `the series of a market outside the listing is read once and kept for the next market of it`() {
        venue.adapter().use { adapter ->
            adapter.instrument(SETTLED)
            adapter.instrument(SETTLED)
        }

        assertThat(venue.server.requests.count { it.url.encodedPath.endsWith("/series/KXHIGHNY") }).isEqualTo(1)
    }

    @Test
    fun `a recording that cannot be pruned is logged and the read it came with is still answered`(
        @org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path,
    ) {
        val stuck = dir.resolve("depth/STUCK/2020-01-01.csv")
        java.nio.file.Files
            .createDirectories(stuck)
        java.nio.file.Files
            .writeString(stuck.resolve("held"), "x")
        val now = System.currentTimeMillis()

        venue.adapter(stateDir = dir, retentionDays = 1).use { adapter ->
            assertThat(adapter.openInterest(CURRY, now - 60_000, now + 60_000)).hasSize(1)
        }
        assertThat(
            java.nio.file.Files
                .exists(stuck),
        ).isTrue
    }
}

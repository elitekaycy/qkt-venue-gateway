package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperStoreRoundTripTest {
    @TempDir lateinit var dir: Path
    private val symbol = "BTC_USDC-PERPETUAL"

    private val ticker =
        DeribitTicker(
            symbol,
            1,
            BigDecimal("99"),
            BigDecimal.TEN,
            BigDecimal("100"),
            BigDecimal.TEN,
            null,
            null,
            null,
            null,
        )

    private fun book() = PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal("0.001"))

    private fun saved(): PaperBook {
        val book = book()
        book.place(
            NewOrder("m-1", symbol, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.GTC),
            ticker,
            1,
        )
        book.place(
            NewOrder("l-1", symbol, Side.BUY, OrderType.LIMIT, BigDecimal.ONE, BigDecimal("90"), null, TimeInForce.GTC),
            ticker,
            2,
        )
        book.state.settlements += VenueSettlement("BTC_USDC-2OCT26-82000-P", BigDecimal("81000"), 3)
        book.funding.records += VenueFunding("tx-1", symbol, BigDecimal("0.5"), "USDC", null, 4)
        book.funding.records += VenueFunding("tx-2", symbol, BigDecimal("0.7"), "USDC", BigDecimal("2"), 5)
        PaperStore(dir).save(book)
        return book
    }

    @Test
    fun `orders, fills, settlements and funding with and without their optional fields come back as they were saved`() {
        val before = saved()

        val after = book().also { PaperStore(dir).load(it) }

        assertThat(after.state.orders).isEqualTo(before.state.orders)
        assertThat(after.state.fills).isEqualTo(before.state.fills)
        assertThat(after.state.settlements).isEqualTo(before.state.settlements)
        assertThat(after.funding.records).isEqualTo(before.funding.records)
        assertThat(after.ledger.positions.keys).isEqualTo(before.ledger.positions.keys)
    }

    private fun without(
        path: List<String>,
        key: String,
    ): String {
        var root = Json.parseToJsonElement(Files.readString(dir.resolve("paper.json"))).jsonObject

        fun edit(
            node: JsonObject,
            rest: List<String>,
        ): JsonObject =
            if (rest.isEmpty()) {
                JsonObject(node - key)
            } else {
                val child = node[rest.first()]!!
                val edited =
                    if (child is JsonArray) {
                        JsonArray(
                            listOf(edit(child.first().jsonObject, rest.drop(1))) + child.drop(1),
                        )
                    } else {
                        edit(child.jsonObject, rest.drop(1))
                    }
                JsonObject(node + (rest.first() to edited))
            }
        root = edit(root, path)
        return root.toString()
    }

    @Test
    fun `a state file missing a field of any record is refused naming the field`() {
        saved()
        val cases =
            listOf(
                listOf("positions") to "avgPrice",
                listOf("settlements") to "price",
                listOf("orders") to "label",
                listOf("fills") to "price",
                listOf("fills", "costs") to "amount",
                listOf("funding") to "amount",
            )

        cases.forEach { (path, key) ->
            val broken = without(path, key)
            val file = dir.resolve("broken-$key-${path.joinToString("-")}")
            Files.createDirectories(file)
            Files.writeString(file.resolve("paper.json"), broken)

            assertThatThrownBy { PaperStore(file).load(book()) }.describedAs("$path $key").hasMessageContaining(key)
        }
    }
}

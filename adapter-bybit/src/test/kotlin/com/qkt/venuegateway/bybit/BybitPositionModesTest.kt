package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.bybit.client.BybitPosition
import com.qkt.venuegateway.bybit.client.BybitPrivateJson
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitPositionModesTest {
    private fun slots(name: String): List<BybitPosition> =
        Json
            .parseToJsonElement(Fixtures.text("private/$name"))
            .jsonObject["result"]!!
            .jsonObject["list"]!!
            .jsonArray
            .map { BybitPrivateJson.position(it.jsonObject) }

    private val oneWay = slots("position-slots-one-way.json")
    private val hedge = slots("position-slots-hedge.json")
    private var now = 0L
    private val reads = mutableListOf<String>()

    private fun modes(
        contracts: Boolean = true,
        hedged: Set<String> = emptySet(),
    ) = BybitPositionModes({
        reads += it
        if (it in
            hedged
        ) {
            hedge
        } else {
            oneWay
        }
    }, { "BTCUSDT" }, contracts, { now }, 600_000)

    private fun order(
        side: Side,
        reduceOnly: Boolean = false,
    ) = NewOrder(
        "x",
        "ETHUSDT",
        side,
        OrderType.MARKET,
        BigDecimal.ONE,
        timeInForce = TimeInForce.GTC,
        reduceOnly = reduceOnly,
    )

    @Test
    fun `a contract in one-way mode is sent at index 0 and the account nets`() {
        val modes = modes()

        assertThat(modes.index(order(Side.BUY))).isZero
        assertThat(modes.index(order(Side.SELL, reduceOnly = true))).isZero
        assertThat(modes.accounting(emptyList())).isEqualTo(Accounting.NETTING)
    }

    @Test
    fun `a contract in hedge mode opens and reduces the slot of its side`() {
        val modes = modes(hedged = setOf("ETHUSDT"))

        assertThat(modes.index(order(Side.BUY))).isEqualTo(1)
        assertThat(modes.index(order(Side.SELL))).isEqualTo(2)
        assertThat(modes.index(order(Side.SELL, reduceOnly = true))).isEqualTo(1)
        assertThat(modes.index(order(Side.BUY, reduceOnly = true))).isEqualTo(2)
    }

    @Test
    fun `the account hedges when a position is held by side, or when its reference contract is set so`() {
        val held = PositionRow("ETHUSDT", BigDecimal.ONE, BigDecimal.TEN, "long")

        assertThat(modes().accounting(listOf(held))).isEqualTo(Accounting.HEDGING)
        assertThat(modes(hedged = setOf("BTCUSDT")).accounting(emptyList())).isEqualTo(Accounting.HEDGING)
    }

    @Test
    fun `a mode is read once and kept until it is old or forgotten`() {
        val modes = modes()
        modes.index(order(Side.BUY))
        modes.index(order(Side.BUY))
        assertThat(reads).containsExactly("ETHUSDT")

        now = 600_000
        modes.index(order(Side.BUY))
        modes.forget("ETHUSDT")
        modes.index(order(Side.BUY))

        assertThat(reads).containsExactly("ETHUSDT", "ETHUSDT", "ETHUSDT")
    }

    @Test
    fun `a spot account holds coins, netted, and sends no position index`() {
        val modes = modes(contracts = false)

        assertThat(modes.index(order(Side.BUY))).isNull()
        assertThat(modes.accounting(emptyList())).isEqualTo(Accounting.NETTING)
        assertThat(reads).isEmpty()
    }
}

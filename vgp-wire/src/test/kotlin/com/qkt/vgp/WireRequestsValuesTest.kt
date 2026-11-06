package com.qkt.vgp

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireRequestsValuesTest {
    private val json = Json

    @Test
    fun `a change is a value of its three optional fields, absent ones staying absent on the wire`() {
        val change = WireChange(quantity = "0.1", limitPrice = "50000")

        assertThat(change.equals(change)).describedAs("a value is equal to itself").isTrue
        assertThat(change).isEqualTo(change.copy()).hasSameHashCodeAs(change.copy())
        assertThat(change).isNotEqualTo(change.copy(stopPrice = "49000")).isNotEqualTo("change")
        assertThat(change.copy(quantity = null)).isNotEqualTo(change)
        assertThat(
            json.encodeToString(WireChange.serializer(), change),
        ).isEqualTo("""{"quantity":"0.1","limit_price":"50000"}""")
        assertThat(json.decodeFromString(WireChange.serializer(), """{"stop_price":"1"}""").stopPrice).isEqualTo("1")
        assertThat(change.toString()).contains("0.1", "50000")
        val (quantity, limit, stop) = change
        assertThat(listOf(quantity, limit, stop)).containsExactly("0.1", "50000", null)
    }

    @Test
    fun `a close is a value of its symbol, quantity, ticket and label, each optional`() {
        val close = WireClose(symbol = "BTC_USDC-PERPETUAL", clientOrderId = "c-1")

        assertThat(close.equals(close)).describedAs("a value is equal to itself").isTrue
        assertThat(close).isEqualTo(close.copy()).hasSameHashCodeAs(close.copy())
        assertThat(close).isNotEqualTo(close.copy(ticket = "t-1")).isNotEqualTo(close.copy(quantity = "1"))
        assertThat(close.copy(symbol = null)).isNotEqualTo(close)
        assertThat(close.copy(clientOrderId = null)).isNotEqualTo(close).isNotEqualTo("close")
        assertThat(json.decodeFromString(WireClose.serializer(), """{"ticket":"t-9","client_order_id":"c-2"}"""))
            .isEqualTo(WireClose(ticket = "t-9", clientOrderId = "c-2"))
        assertThat(close.toString()).contains("BTC_USDC-PERPETUAL", "c-1")
        val (symbol, quantity, ticket, label) = close
        assertThat(listOf(symbol, quantity, ticket, label)).containsExactly("BTC_USDC-PERPETUAL", null, null, "c-1")
    }

    @Test
    fun `equal requests hash alike whether their optional fields are all absent or all given`() {
        assertThat(WireChange()).hasSameHashCodeAs(WireChange()).isEqualTo(WireChange())
        assertThat(
            WireChange("1", "2", "3"),
        ).hasSameHashCodeAs(WireChange("1", "2", "3")).isEqualTo(WireChange("1", "2", "3"))
        assertThat(WireClose()).hasSameHashCodeAs(WireClose()).isEqualTo(WireClose())
        assertThat(
            WireClose("S", "1", "t", "c"),
        ).hasSameHashCodeAs(WireClose("S", "1", "t", "c")).isEqualTo(WireClose("S", "1", "t", "c"))
        assertThat(WireChange().equals(null)).isFalse
        assertThat(WireClose().equals(null)).isFalse
    }
}

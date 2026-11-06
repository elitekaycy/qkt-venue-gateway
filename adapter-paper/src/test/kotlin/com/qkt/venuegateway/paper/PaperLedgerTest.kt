package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperLedgerTest {
    private val symbol = "BTC_USDC-PERPETUAL"
    private val ledger = PaperLedger(BigDecimal("10000"))

    private fun held() = ledger.positions.getValue(symbol)

    @Test
    fun `adding to a position averages its entry, and a partial close keeps the entry and realizes the part closed`() {
        ledger.apply(symbol, Side.BUY, BigDecimal("1"), BigDecimal("100"))
        ledger.apply(symbol, Side.BUY, BigDecimal("1"), BigDecimal("110"))
        assertThat(held().quantity).isEqualByComparingTo("2")
        assertThat(held().avgPrice).isEqualByComparingTo("105")

        ledger.apply(symbol, Side.SELL, BigDecimal("1"), BigDecimal("120"))

        assertThat(held().quantity).isEqualByComparingTo("1")
        assertThat(held().avgPrice).isEqualByComparingTo("105")
        assertThat(ledger.balance).isEqualByComparingTo("10015")
    }

    @Test
    fun `closing a short realizes the fall in price into cash and leaves nothing held`() {
        ledger.apply(symbol, Side.SELL, BigDecimal("2"), BigDecimal("100"))
        ledger.apply(symbol, Side.BUY, BigDecimal("2"), BigDecimal("90"))

        assertThat(ledger.positions).isEmpty()
        assertThat(ledger.balance).isEqualByComparingTo("10020")
    }

    @Test
    fun `a reduce-only buy may cover a short up to its size and no further`() {
        ledger.apply(symbol, Side.SELL, BigDecimal("2"), BigDecimal("100"))

        assertThat(ledger.reduceOnlyRefusal(symbol, Side.BUY, BigDecimal("2"))).isNull()
        assertThat(ledger.reduceOnlyRefusal(symbol, Side.BUY, BigDecimal("3")))
            .isEqualTo("reduce_only order would not reduce the position")
        assertThat(ledger.reduceOnlyRefusal(symbol, Side.SELL, BigDecimal("1"))).isNotNull()
        assertThat(ledger.reduceOnlyRefusal("ETH_USDC-PERPETUAL", Side.SELL, BigDecimal("1"))).isNotNull()
    }

    @Test
    fun `an unmarked position counts at its entry in equity, and settling nothing held changes nothing`() {
        ledger.apply(symbol, Side.BUY, BigDecimal("1"), BigDecimal("100"))

        assertThat(ledger.equity { null }).isEqualByComparingTo("10000")
        assertThat(ledger.equity { BigDecimal("130") }).isEqualByComparingTo("10030")
        assertThat(ledger.settle("ETH_USDC-PERPETUAL", BigDecimal("1"))).isFalse()
        assertThat(ledger.balance).isEqualByComparingTo("10000")
    }
}

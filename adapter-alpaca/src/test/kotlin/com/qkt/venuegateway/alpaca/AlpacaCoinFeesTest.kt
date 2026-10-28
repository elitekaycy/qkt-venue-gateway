package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.support.JsonReads.decimal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A coin bought is reported as Alpaca filled it, its fee a commission in the coin: quantity less fee is the position. */
class AlpacaCoinFeesTest {
    private val updates = Fixtures.updates("ws-trade-updates-fill.json").filter { AlpacaMapping.fill(it) != null }
    private val held = Fixtures.array("positions-crypto.json").single().decimal("qty")!!

    @Test
    fun `two partial buys from flat, less their coin fees, add up to Alpaca's position`() {
        val fees = AlpacaCoinFees { emptyList() }.apply { seed() }
        val fills = updates.map { fees.received(AlpacaMapping.fill(it)!!, it.decimal("position_qty"), coin = "BTC") }
        val received =
            fills.map { f ->
                f.quantity -
                    f.costs.filter { it.currency == "BTC" }.fold(BigDecimal.ZERO) { a, c -> a + c.amount }
            }
        assertThat(received.fold(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(held)
        assertThat(received.map { it.toPlainString() }).containsExactly("0.000067898", "0.000131601")
        val first = fills.first()
        // 0.000068069 bought (as Alpaca filled it), 0.000067898 received: a 0.000000171 BTC commission.
        assertThat(first.quantity.toPlainString()).isEqualTo("0.000068069")
        val cost = first.costs.single()
        assertThat(cost.kind).isEqualTo(CostKind.COMMISSION)
        assertThat(cost.currency).isEqualTo("BTC")
        assertThat(cost.amount).isEqualByComparingTo("0.000000171")
    }

    @Test
    fun `a share, or a coin position kept stale by a trade elsewhere, is reported as Alpaca filled it`() {
        val update = updates.first()
        val gross = AlpacaMapping.fill(update)!!
        val shares = AlpacaCoinFees { emptyList() }.apply { seed() }
        assertThat(shares.received(gross, update.decimal("position_qty"), coin = null)).isEqualTo(gross)
        val stale = AlpacaCoinFees { listOf(PositionRow("BTCUSD", BigDecimal("0.5"), BigDecimal.ONE)) }.apply { seed() }
        assertThat(stale.received(gross, update.decimal("position_qty"), coin = "BTC")).isEqualTo(gross)
        assertThat(AlpacaCoinFees { emptyList() }.received(gross, null, coin = "BTC")).isEqualTo(gross)
    }

    @Test
    fun `a coin bought and sold leaves the reported position at Alpaca's zero, with no dust`() {
        val fees = AlpacaCoinFees { emptyList() }.apply { seed() }
        val fills =
            Fixtures.updates("ws-trade-updates-coin-roundtrip.json").mapNotNull { u ->
                AlpacaMapping.fill(u)?.let { fees.received(it, u.decimal("position_qty"), coin = "BTC") }
            }
        val (buy, sell) = fills
        assertThat(buy.quantity.toPlainString()).isEqualTo("0.0002")
        val fee = buy.costs.single()
        assertThat(fee.currency).isEqualTo("BTC")
        assertThat(fee.amount).isEqualByComparingTo("0.0000005")
        // A sale pays its fee in dollars: it moves the position by what it sold, and is reported as filled.
        assertThat(sell.costs).isEmpty()
        assertThat(buy.quantity - fee.amount - sell.quantity).isZero
    }
}

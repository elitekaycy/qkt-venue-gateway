package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.support.JsonReads.decimal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A coin bought is reported at what Alpaca's position received, its coin fee a USD commission at the fill price. */
class AlpacaCoinFeesTest {
    private val updates = Fixtures.updates("ws-trade-updates-fill.json").filter { AlpacaMapping.fill(it) != null }
    private val held = Fixtures.array("positions-crypto.json").single().decimal("qty")!!

    @Test
    fun `two partial buys from flat add up to Alpaca's position, and each fee is charged in dollars`() {
        val fees = AlpacaCoinFees { emptyList() }.apply { seed() }
        val fills = updates.map { fees.received(AlpacaMapping.fill(it)!!, it.decimal("position_qty"), coin = true) }
        assertThat(fills.fold(BigDecimal.ZERO) { a, f -> a + f.quantity }).isEqualByComparingTo(held)
        assertThat(fills.map { it.quantity.toPlainString() }).containsExactly("0.000067898", "0.000131601")
        val first = fills.first()
        val cost = first.costs.single()
        assertThat(cost.kind).isEqualTo(CostKind.COMMISSION)
        assertThat(cost.currency).isEqualTo("USD")
        // 0.000068069 bought, 0.000067898 received: 0.000000171 BTC at 80777.77.
        assertThat(
            cost.amount,
        ).isEqualByComparingTo(
            BigDecimal("0.000000171").multiply(BigDecimal("80777.77")).setScale(8, java.math.RoundingMode.HALF_UP),
        )
    }

    @Test
    fun `a share, or a coin position kept stale by a trade elsewhere, is reported as Alpaca filled it`() {
        val update = updates.first()
        val gross = AlpacaMapping.fill(update)!!
        val shares = AlpacaCoinFees { emptyList() }.apply { seed() }
        assertThat(shares.received(gross, update.decimal("position_qty"), coin = false)).isEqualTo(gross)
        val stale = AlpacaCoinFees { listOf(PositionRow("BTCUSD", BigDecimal("0.5"), BigDecimal.ONE)) }.apply { seed() }
        assertThat(stale.received(gross, update.decimal("position_qty"), coin = true)).isEqualTo(gross)
        assertThat(AlpacaCoinFees { emptyList() }.received(gross, null, coin = true)).isEqualTo(gross)
    }

    @Test
    fun `a coin bought and sold leaves the reported position at Alpaca's zero, with no dust`() {
        val fees = AlpacaCoinFees { emptyList() }.apply { seed() }
        val fills =
            Fixtures.updates("ws-trade-updates-coin-roundtrip.json").mapNotNull { u ->
                AlpacaMapping.fill(u)?.let { fees.received(it, u.decimal("position_qty"), coin = true) }
            }
        val (buy, sell) = fills
        assertThat(buy.quantity.toPlainString()).isEqualTo("0.0001995")
        assertThat(buy.costs.single().amount).isPositive
        // A sale pays its fee in dollars: it moves the position by what it sold, and is reported as filled.
        assertThat(sell.quantity).isEqualByComparingTo(buy.quantity)
        assertThat(sell.costs).isEmpty()
        assertThat(buy.quantity - sell.quantity).isZero
    }
}

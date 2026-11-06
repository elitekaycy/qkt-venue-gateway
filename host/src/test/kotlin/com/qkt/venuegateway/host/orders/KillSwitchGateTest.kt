package com.qkt.venuegateway.host.orders

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.host.FakeAdapter
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.journal.setKillSwitch
import com.qkt.vgp.WireKillSwitch
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KillSwitchGateTest {
    private val symbol = "BTC_USDC-PERPETUAL"
    private val venue = FakeAdapter()

    private fun order(
        side: Side,
        quantity: String,
        reduceOnly: Boolean = true,
    ) = NewOrder("k-1", symbol, side, OrderType.MARKET, BigDecimal(quantity), null, null, TimeInForce.IOC, reduceOnly)

    private fun gate(dir: Path) =
        Journal.open(dir.resolve("j.db")).let {
            it.setKillSwitch(WireKillSwitch(all = false, symbols = listOf(symbol)))
            KillSwitchGate(it, venue)
        }

    @Test
    fun `under a covering scope only a reduce-only order on the side that shrinks the position passes`(
        @TempDir dir: Path,
    ) {
        val gate = gate(dir)
        venue.net[symbol] = BigDecimal("-0.3")

        assertThat(gate.refusal(order(Side.BUY, "0.3"))).isNull()
        assertThat(gate.refusal(order(Side.SELL, "0.1"))).contains("only orders that reduce the account pass")
        assertThat(gate.refusal(order(Side.BUY, "0.4"))).isNotNull
        assertThat(gate.refusal(order(Side.BUY, "0.1", reduceOnly = false))).isNotNull
        venue.net[symbol] = BigDecimal("0.3")
        assertThat(gate.refusal(order(Side.SELL, "0.3"))).isNull()
        assertThat(gate.refusal(order(Side.BUY, "0.1"))).isNotNull
    }

    @Test
    fun `a flat position has nothing to reduce, and a symbol outside the scope passes untouched`(
        @TempDir dir: Path,
    ) {
        val gate = gate(dir)

        assertThat(gate.refusal(order(Side.SELL, "0.1"))).isNotNull
        assertThat(gate.covers("ETH_USDC-PERPETUAL")).isFalse
        assertThat(gate.refusal(order(Side.BUY, "1", reduceOnly = false).copy(symbol = "ETH_USDC-PERPETUAL"))).isNull()
    }
}

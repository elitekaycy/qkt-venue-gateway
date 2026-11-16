package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperStoreTest {
    private val symbol = "BTC_USDC-PERPETUAL"

    private fun book() = PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal("0.001"))

    private val ticker =
        DeribitTicker(
            symbol,
            1,
            BigDecimal("99"),
            BigDecimal.ONE,
            BigDecimal("100"),
            BigDecimal.ONE,
            null,
            null,
            null,
            null,
        )

    @Test
    fun `a fill's commission and a working stop-limit's prices survive a restart exactly`(
        @TempDir dir: Path,
    ) {
        val book = book()
        val market = NewOrder("m", symbol, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.GTC)
        book.place(market, ticker, 1)
        val stopLimit =
            NewOrder(
                "sl",
                symbol,
                Side.SELL,
                OrderType.STOP_LIMIT,
                BigDecimal.ONE,
                BigDecimal("94.5"),
                BigDecimal("95"),
                TimeInForce.GTC,
                reduceOnly = true,
            )
        book.place(stopLimit, ticker, 2)
        PaperStore(dir).save(book)

        val restored = book().also { PaperStore(dir).load(it) }

        val fill = restored.state.fills.single()
        val cost = fill.costs.single()
        assertThat(cost.kind).isEqualTo(CostKind.COMMISSION)
        assertThat(cost.amount.toPlainString()).isEqualTo("0.100")
        assertThat(cost.currency).isEqualTo("USDC")
        assertThat(restored.ledger.balance.toPlainString()).isEqualTo("9999.900")
        val working = restored.state.orders.getValue("sl")
        assertThat(working.status).isEqualTo(OrderStatus.WORKING)
        assertThat(working.limitPrice?.toPlainString()).isEqualTo("94.5")
        assertThat(working.stopPrice?.toPlainString()).isEqualTo("95")
        assertThat(working.avgFillPrice).isNull()
        assertThat(working.reduceOnly).isTrue()
        assertThat(restored.state.fillCount).isEqualTo(1)
    }

    @Test
    fun `a state file written before funding was kept loads with no funding charged`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("paper.json"),
            """
            {"balance":"9500.5","positions":[{"symbol":"$symbol","quantity":"-2","avgPrice":"101.25"}],
            "orders":[],"fills":[],"settlements":[{"symbol":"BTC_USDC-2OCT26","price":"81000","timeMs":7}],
            "triggered":["s-1"],"fillCount":3}
            """.trimIndent(),
        )

        val restored = book().also { PaperStore(dir).load(it) }

        assertThat(restored.ledger.balance).isEqualByComparingTo("9500.5")
        assertThat(restored.ledger.positions.getValue(symbol)).isEqualTo(
            PaperPosition(BigDecimal("-2"), BigDecimal("101.25")),
        )
        val settlement = restored.state.settlements.single()
        assertThat(settlement.price).isEqualByComparingTo("81000")
        assertThat(restored.state.triggered).containsExactly("s-1")
        assertThat(restored.state.fillCount).isEqualTo(3)
        assertThat(restored.funding.records).isEmpty()
        assertThat(restored.funding.chargedThrough).isEmpty()
    }

    @Test
    fun `a missing state file leaves the book as built`(
        @TempDir dir: Path,
    ) {
        val restored = book().also { PaperStore(dir.resolve("absent")).load(it) }

        assertThat(restored.ledger.balance).isEqualByComparingTo("10000")
        assertThat(restored.state.orders).isEmpty()
    }
}

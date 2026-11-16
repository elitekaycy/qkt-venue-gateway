package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperRemainingArmsTest {
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

    private fun book() = PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal.ZERO)

    @Test
    fun `a reduce-only order with nothing held is rejected as one that would not reduce`() {
        val book = book()
        val close =
            NewOrder(
                "c-1",
                symbol,
                Side.SELL,
                OrderType.MARKET,
                BigDecimal.ONE,
                timeInForce = TimeInForce.GTC,
                reduceOnly = true,
            )

        book.place(close, ticker, 1)

        val rejected = book.state.orders.getValue("c-1")
        assertThat(rejected.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(rejected.rejectReason).contains("would not reduce")
    }

    @Test
    fun `the funding charged through each perpetual survives a restart`(
        @TempDir dir: Path,
    ) {
        val book = book()
        book.funding.chargedThrough[symbol] = 1_791_000_000_000L
        PaperStore(dir).save(book)

        val restored = book().also { PaperStore(dir).load(it) }

        assertThat(restored.funding.chargedThrough).containsEntry(symbol, 1_791_000_000_000L)
    }
}

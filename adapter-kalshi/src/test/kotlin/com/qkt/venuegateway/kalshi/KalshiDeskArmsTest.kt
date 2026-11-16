package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.RESTING
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiDeskArmsTest {
    private val venue = KalshiVenue()
    private val adapter = venue.adapter()

    @AfterEach
    fun stop() {
        adapter.close()
        venue.close()
    }

    private fun order(
        label: String,
        side: Side,
        symbol: String = CURRY,
        reduceOnly: Boolean = false,
    ) = NewOrder(
        label,
        symbol,
        side,
        OrderType.LIMIT,
        BigDecimal.ONE,
        BigDecimal("0.01"),
        null,
        TimeInForce.GTC,
        reduceOnly,
    )

    @Test
    fun `a cancel waits through a read that finds no order and one still resting until the order has ended`() {
        adapter.place(order("qkt-probe-rest", Side.BUY))
        venue.restingReads +=
            listOf("private/order-resting.json", "!404 private/error-not-found.json", "private/order-resting.json")

        adapter.cancel("qkt-probe-rest")

        val reads = venue.server.requests.count { it.method == "GET" && it.url.encodedPath.endsWith(RESTING) }
        assertThat(
            reads,
        ).describedAs("the read-back, the find, a 404, a resting read and the ended one").isGreaterThanOrEqualTo(5)
        assertThat(venue.restingReads).isEmpty()
    }

    @Test
    fun `a resting reduce-only sell of a market the account does not hold is refused before kalshi`() {
        val unheld = "KXNBA3D-26NOV05MIAGSW-GSWDGREEN23"
        val posts = { venue.server.requests.count { it.method == "POST" } }
        val before = posts()

        assertThatThrownBy { adapter.place(order("shrink", Side.SELL, unheld, reduceOnly = true)) }
            .hasMessageContaining("no position to reduce")
        assertThat(posts()).isEqualTo(before)
    }

    @Test
    fun `a print kalshi answers outside the range asked, before it or at its end, is not served`() {
        val printed = 1_793_927_017_046L

        assertThat(adapter.trades(CURRY, printed + 1, printed + 60_000, 10)).isEmpty()
        assertThat(adapter.trades(CURRY, printed - 60_000, printed, 10)).isEmpty()
        assertThat(adapter.trades(CURRY, printed, printed + 1, 10).map { it.timeMs }).containsExactly(printed)
    }
}

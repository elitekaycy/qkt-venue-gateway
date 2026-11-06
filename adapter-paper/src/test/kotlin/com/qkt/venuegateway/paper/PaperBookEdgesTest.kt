package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperBookEdgesTest {
    private val symbol = "BTC_USDC-PERPETUAL"
    private val book = PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal.ZERO)

    private fun ticker(
        bid: String,
        ask: String,
    ) = DeribitTicker(
        symbol,
        1,
        BigDecimal(bid),
        BigDecimal.ONE,
        BigDecimal(ask),
        BigDecimal.ONE,
        null,
        null,
        null,
        null,
    )

    private fun order(
        id: String,
        side: Side,
        type: OrderType = OrderType.MARKET,
        limit: String? = null,
        stop: String? = null,
        tif: TimeInForce = TimeInForce.GTC,
        reduceOnly: Boolean = false,
    ) = NewOrder(
        id,
        symbol,
        side,
        type,
        BigDecimal.ONE,
        limit?.let(::BigDecimal),
        stop?.let(::BigDecimal),
        tif,
        reduceOnly,
    )

    @Test
    fun `cancelling an order that already ended returns it unchanged, and an unknown label returns nothing`() {
        book.place(order("m", Side.BUY), ticker("99", "100"), 1)

        val again = book.cancel("m", 2)

        assertThat(again?.status).isEqualTo(OrderStatus.FILLED)
        assertThat(again?.updatedAtMs).isEqualTo(1)
        assertThat(book.cancel("never-placed", 3)).isNull()
    }

    @Test
    fun `settling a symbol with nothing held makes no settlement`() {
        assertThat(book.settle(symbol, BigDecimal("100"), 5)).isNull()
        assertThat(book.state.settlements).isEmpty()
        assertThat(book.ledger.balance).isEqualByComparingTo("10000")
    }

    @Test
    fun `a resting reduce-only limit is cancelled when the position it would reduce is gone by the time it fills`() {
        book.place(order("open", Side.BUY), ticker("99", "100"), 1)
        val cutOrder = order("cut", Side.SELL, OrderType.LIMIT, limit = "110", reduceOnly = true)
        val resting = book.place(cutOrder, ticker("99", "100"), 2)
        book.place(order("close", Side.SELL), ticker("99", "100"), 3)

        val reached = book.onTicker(ticker("111", "112"), 4)

        assertThat(resting.orders.single().status).isEqualTo(OrderStatus.WORKING)
        assertThat(reached.fills).isEmpty()
        val cut = reached.orders.single()
        assertThat(cut.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(cut.rejectReason).isEqualTo("reduce_only order would not reduce the position")
        assertThat(book.ledger.positions).isEmpty()
    }

    @Test
    fun `a sell stop waits until the bid falls to it, then fills at the bid`() {
        val placed = book.place(order("ss", Side.SELL, OrderType.STOP, stop = "95"), ticker("99", "100"), 1)
        assertThat(book.onTicker(ticker("96", "97"), 2).fills).isEmpty()

        val fired = book.onTicker(ticker("94", "95"), 3)

        assertThat(placed.orders.single().status).isEqualTo(OrderStatus.WORKING)
        assertThat(fired.fills.single().price).isEqualByComparingTo("94")
        val position = book.ledger.positions.getValue(symbol)
        assertThat(position.quantity).isEqualByComparingTo("-1")
    }

    @Test
    fun `a resting sell limit fills at its limit once the bid reaches it`() {
        book.place(order("sl", Side.SELL, OrderType.LIMIT, limit = "105"), ticker("99", "100"), 1)

        assertThat(book.onTicker(ticker("104", "106"), 2).fills).isEmpty()
        val reached = book.onTicker(ticker("107", "108"), 3)
        assertThat(reached.fills.single().price).isEqualByComparingTo("105")
    }

    @Test
    fun `with no ticker known a market order is rejected while a limit and a stop wait`() {
        val market = book.place(order("m", Side.BUY), null, 1).orders.single()
        val limit = book.place(order("l", Side.BUY, OrderType.LIMIT, limit = "100"), null, 2).orders.single()
        val stop = book.place(order("s", Side.BUY, OrderType.STOP, stop = "105"), null, 3).orders.single()

        assertThat(market.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(market.rejectReason).isEqualTo("the buy side is not quoted")
        assertThat(limit.status).isEqualTo(OrderStatus.WORKING)
        assertThat(stop.status).isEqualTo(OrderStatus.WORKING)
        assertThat(book.quoted()).containsExactly(symbol, symbol)
    }

    @Test
    fun `a fill-or-kill stop-limit whose limit the touch has passed when it triggers is cancelled`() {
        book.place(
            order("fok", Side.BUY, OrderType.STOP_LIMIT, limit = "104", stop = "105", tif = TimeInForce.FOK),
            ticker("99", "100"),
            1,
        )

        val triggered = book.onTicker(ticker("105", "106"), 2)

        assertThat(triggered.fills).isEmpty()
        assertThat(triggered.orders.single().status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(book.state.triggered).doesNotContain("fok")
    }

    @Test
    fun `the book quotes the codes of working orders and held positions`() {
        book.place(order("rest", Side.BUY, OrderType.LIMIT, limit = "90"), ticker("99", "100"), 1)
        book.ledger.apply("ETH_USDC-PERPETUAL", Side.BUY, BigDecimal.ONE, BigDecimal("3000"))

        assertThat(book.quoted()).containsExactly(symbol, "ETH_USDC-PERPETUAL")
        assertThat(book.working().map { it.clientOrderId }).containsExactly("rest")
    }
}

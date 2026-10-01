package com.qkt.venued.paper

import com.qkt.venued.adapter.CostKind
import com.qkt.venued.adapter.NewOrder
import com.qkt.venued.adapter.OrderStatus
import com.qkt.venued.adapter.OrderType
import com.qkt.venued.adapter.Side
import com.qkt.venued.adapter.TimeInForce
import com.qkt.venued.deribit.DeribitTicker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperBookTest {
    private val symbol = "BTC_USDC-PERPETUAL"

    private fun book(fee: String = "0") =
        PaperBook(
            PaperLedger(BigDecimal("10000")) {
                BigDecimal.ONE
            },
            "USDC",
            BigDecimal(fee),
        ) { BigDecimal.ONE }

    private fun ticker(
        bid: String?,
        ask: String?,
    ) = DeribitTicker(
        symbol,
        1,
        bid?.let(::BigDecimal),
        bid?.let {
            BigDecimal.ONE
        },
        ask?.let(::BigDecimal),
        ask?.let { BigDecimal.ONE },
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
        quantity: String = "1",
    ) = NewOrder(
        id,
        symbol,
        side,
        type,
        BigDecimal(quantity),
        limit?.let(::BigDecimal),
        stop?.let(::BigDecimal),
        tif,
        reduceOnly,
    )

    @Test
    fun `a market order fills whole at the touch, and is rejected when that side is not quoted`() {
        val book = book()
        val buy = book.place(order("b", Side.BUY), ticker("99", "101"), 5)
        val noBid = book.place(order("s", Side.SELL), ticker(null, "101"), 6)

        assertThat(buy.fills.single().price).isEqualByComparingTo("101")
        assertThat(buy.orders.single().status).isEqualTo(OrderStatus.FILLED)
        assertThat(noBid.orders.single().status).isEqualTo(OrderStatus.REJECTED)
        assertThat(noBid.fills).isEmpty()
    }

    @Test
    fun `a resting limit fills at its limit once the touch reaches it, a marketable one at the touch`() {
        val book = book()
        assertThat(
            book.place(order("l1", Side.BUY, OrderType.LIMIT, limit = "100"), ticker("99", "101"), 1).fills,
        ).isEmpty()
        val later = book.onTicker(ticker("98", "99.5"), 2)
        val marketable = book.place(order("l2", Side.BUY, OrderType.LIMIT, limit = "100"), ticker("98", "99.5"), 3)

        assertThat(later.fills.single().price).isEqualByComparingTo("100")
        assertThat(marketable.fills.single().price).isEqualByComparingTo("99.5")
        assertThat(
            book
                .place(
                    order("l3", Side.BUY, OrderType.LIMIT, limit = "90", tif = TimeInForce.IOC),
                    ticker("98", "99.5"),
                    4,
                ).orders
                .single()
                .status,
        ).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a stop fills at the touch once crossed, and a triggered stop-limit that rests fills at its limit`() {
        val book = book()
        assertThat(
            book.place(order("st", Side.BUY, OrderType.STOP, stop = "105"), ticker("99", "101"), 1).fills,
        ).isEmpty()
        assertThat(
            book
                .onTicker(ticker("104", "106"), 2)
                .fills
                .single()
                .price,
        ).isEqualByComparingTo("106")

        book.place(order("sl", Side.BUY, OrderType.STOP_LIMIT, limit = "107", stop = "108"), ticker("104", "106"), 3)
        assertThat(book.onTicker(ticker("107", "109"), 4).fills).isEmpty()
        assertThat(
            book
                .onTicker(ticker("105", "106.5"), 5)
                .fills
                .single()
                .price,
        ).isEqualByComparingTo("107")
    }

    @Test
    fun `reduce-only never grows or flips the position`() {
        val book = book()
        book.place(order("open", Side.BUY, quantity = "2"), ticker("99", "100"), 1)

        val flip = book.place(order("flip", Side.SELL, reduceOnly = true, quantity = "3"), ticker("99", "100"), 2)
        val grow = book.place(order("grow", Side.BUY, reduceOnly = true), ticker("99", "100"), 3)
        val reduce = book.place(order("cut", Side.SELL, reduceOnly = true), ticker("99", "100"), 4)

        assertThat(flip.orders.single().status).isEqualTo(OrderStatus.REJECTED)
        assertThat(grow.orders.single().status).isEqualTo(OrderStatus.REJECTED)
        assertThat(reduce.fills).hasSize(1)
        assertThat(
            book.ledger.positions
                .getValue(symbol)
                .quantity,
        ).isEqualByComparingTo("1")
    }

    @Test
    fun `realized P&L, flips, settlement and equity add up, and a fee rate charges commission`() {
        val book = book(fee = "0.001")
        book.place(order("a", Side.BUY, quantity = "2"), ticker("99", "100"), 1)
        book.place(order("b", Side.SELL, quantity = "3"), ticker("110", "111"), 2)

        val position = book.ledger.positions.getValue(symbol)
        assertThat(position.quantity).isEqualByComparingTo("-1")
        assertThat(position.avgPrice).isEqualByComparingTo("110")
        val fees = BigDecimal("0.2").add(BigDecimal("0.33"))
        assertThat(book.ledger.balance).isEqualByComparingTo(BigDecimal("10020").subtract(fees))
        assertThat(
            book.state.fills
                .last()
                .costs
                .single()
                .kind,
        ).isEqualTo(CostKind.COMMISSION)
        assertThat(book.ledger.equity { BigDecimal("105") }).isEqualByComparingTo(BigDecimal("10025").subtract(fees))

        book.settle(symbol, BigDecimal("100"), 3)
        assertThat(book.ledger.positions).isEmpty()
        assertThat(book.ledger.balance).isEqualByComparingTo(BigDecimal("10030").subtract(fees))
    }
}

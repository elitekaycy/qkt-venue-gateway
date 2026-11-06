package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Cost
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.deribit.client.DeribitTicker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaperRecordsArmsTest {
    private val symbol = "BTC_USDC-PERPETUAL"
    private val ten = BigDecimal("10")

    private fun ticker(
        name: String,
        bid: String,
        ask: String,
    ) = DeribitTicker(name, 1, BigDecimal(bid), ten, BigDecimal(ask), ten, null, null, null, null)

    private fun book() = PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal.ZERO)

    private fun limit(
        id: String,
        symbol: String,
        side: Side,
        price: String,
    ) = NewOrder(id, symbol, side, OrderType.LIMIT, BigDecimal.ONE, BigDecimal(price), timeInForce = TimeInForce.GTC)

    private val working =
        VenueOrder(
            "o-1",
            "v-1",
            symbol,
            Side.BUY,
            OrderType.STOP_LIMIT,
            BigDecimal("2.50"),
            BigDecimal("94.5"),
            BigDecimal("95"),
            TimeInForce.IOC,
            true,
            OrderStatus.REJECTED,
            BigDecimal("1.25"),
            BigDecimal("94.75"),
            "no liquidity",
            5L,
            9L,
        )
    private val fill =
        VenueFill(
            "o-1",
            "v-1",
            "f-1",
            symbol,
            Side.SELL,
            BigDecimal("1.50"),
            BigDecimal("94.5"),
            7L,
            listOf(Cost(CostKind.COMMISSION, BigDecimal("0.10"), "USDC")),
        )

    @Test
    fun `an order and a fill keep every field, exactly, through the records the state file holds`() {
        assertThat(Order(working).order()).isEqualTo(working)
        val bare =
            working.copy(
                venueOrderId = null,
                limitPrice = null,
                stopPrice = null,
                avgFillPrice = null,
                rejectReason = null,
            )
        assertThat(Order(bare).order()).isEqualTo(bare)
        assertThat(Fill(fill).fill()).isEqualTo(fill)
        assertThat(Fill(fill.copy(venueOrderId = null, costs = emptyList())).fill())
            .isEqualTo(fill.copy(venueOrderId = null, costs = emptyList()))
    }

    @Test
    fun `a funding record keeps the position it was charged on, or none`() {
        val held = VenueFunding("tx-1", symbol, BigDecimal("0.05"), "USDC", BigDecimal("2"), 11L)

        assertThat(FundingRecord(held).funding()).isEqualTo(held)
        assertThat(FundingRecord(held.copy(position = null)).funding()).isEqualTo(held.copy(position = null))
    }

    @Test
    fun `the records are values, equal when their fields are, different when any one differs`() {
        val order = Order(working)
        val record = Fill(fill)
        val position = Position(symbol, "1", "94.5")
        val settlement = Settlement(symbol, "95", 3L)
        val charge = Charge("COMMISSION", "0.1", "USDC")
        val funding = FundingRecord(VenueFunding("tx-1", symbol, BigDecimal.ONE, "USDC", null, 11L))

        assertThat(
            order,
        ).isEqualTo(order.copy()).hasSameHashCodeAs(order.copy()).isNotEqualTo(order.copy(label = "o-2"))
        assertThat(order.copy(reason = null)).isNotEqualTo(order).isNotEqualTo("order")
        assertThat(
            record,
        ).isEqualTo(record.copy()).hasSameHashCodeAs(record.copy()).isNotEqualTo(record.copy(fillId = "f-2"))
        assertThat(
            position,
        ).isEqualTo(position.copy()).hasSameHashCodeAs(position.copy()).isNotEqualTo(position.copy(quantity = "2"))
        assertThat(
            settlement,
        ).isEqualTo(settlement.copy()).hasSameHashCodeAs(settlement.copy()).isNotEqualTo(settlement.copy(timeMs = 4L))
        assertThat(
            charge,
        ).isEqualTo(charge.copy()).hasSameHashCodeAs(charge.copy()).isNotEqualTo(charge.copy(amount = "0.2"))
        assertThat(
            funding,
        ).isEqualTo(funding.copy()).hasSameHashCodeAs(funding.copy()).isNotEqualTo(funding.copy(position = "1"))
        assertThat(listOf(order, record, position, settlement, charge, funding).map { it.toString() })
            .allSatisfy { assertThat(it).isNotBlank() }
        val (label, venueId) = order
        assertThat(label to venueId).isEqualTo("o-1" to "v-1")
    }

    @Test
    fun `a ticker matches only the working orders of its own instrument`() {
        val book = book()
        val other = "ETH_USDC-PERPETUAL"
        book.place(limit("mine", symbol, Side.BUY, "99"), ticker(symbol, "98", "100"), 1)
        book.place(limit("gone", symbol, Side.BUY, "99"), ticker(symbol, "98", "100"), 2)
        book.cancel("gone", 3)
        book.place(limit("theirs", other, Side.BUY, "99"), ticker(other, "98", "100"), 4)

        val change = book.onTicker(ticker(symbol, "97", "98.5"), 5)

        assertThat(change.orders.map { it.clientOrderId }).containsExactly("mine")
        assertThat(
            book.state.orders
                .getValue("theirs")
                .status,
        ).isEqualTo(OrderStatus.WORKING)
        assertThat(
            book.state.orders
                .getValue("gone")
                .status,
        ).isEqualTo(OrderStatus.CANCELLED)
    }

    @Test
    fun `a stop-limit that triggers later fills at the touch it triggered on, not its limit`() {
        val book = book()
        val stop =
            NewOrder(
                "sl",
                symbol,
                Side.SELL,
                OrderType.STOP_LIMIT,
                BigDecimal.ONE,
                BigDecimal("94"),
                BigDecimal("95"),
                TimeInForce.GTC,
            )
        assertThat(book.place(stop, ticker(symbol, "99", "100"), 1).fills).isEmpty()

        val change = book.onTicker(ticker(symbol, "94.8", "95.2"), 2)

        assertThat(change.fills.single().price).isEqualByComparingTo("94.8")
    }
}

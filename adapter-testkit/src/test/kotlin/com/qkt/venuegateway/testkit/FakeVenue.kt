package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueRefusedException
import java.math.BigDecimal

/**
 * An in-memory venue keeping the whole adapter contract: limits rest, market orders fill up to [depth] (reduce-only
 * ones up to [closeDepth]) and end, every optional history answers in shape when declared and is refused when not.
 * Tests break one promise at a time by changing a setting or overriding one call.
 */
internal open class FakeVenue(
    val book: FakeBook = FakeBook(),
) : FakeHistories() {
    override val id = "fake"
    override val version = "1"
    override val klineResolutions = setOf(MINUTE, 5 * MINUTE)

    var depth: BigDecimal = BigDecimal("1000")
    var closeDepth: BigDecimal = BigDecimal("1000")

    /** Market orders rest instead of filling, as a venue with a stuck remainder. */
    var marketRests = false
    var listener: AdapterListener? = null

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        listener.connection(true, "fake link up")
    }

    override fun identity() = VenueIdentity("fake-1", TradeMode.DEMO, "USD")

    override fun instruments() =
        listOf(
            Instrument(PERP, InstrumentKind.PERPETUAL, "USD", BigDecimal.ONE, BigDecimal("0.5"), STEP, STEP),
            Instrument(FUTURE, InstrumentKind.FUTURE, "USD", BigDecimal.ONE, BigDecimal("0.5"), STEP, STEP, EXPIRY),
            Instrument(
                OPTION,
                InstrumentKind.OPTION,
                "USD",
                BigDecimal.ONE,
                BigDecimal("0.01"),
                STEP,
                STEP,
                EXPIRY,
                PRICE,
                "call",
                "FAKE",
            ),
        )

    override fun account() = AccountSnapshot("USD", PRICE, PRICE, BigDecimal.ZERO, PRICE)

    override fun positions() =
        Positions(
            Accounting.NETTING,
            book.net.filter { it.value.signum() != 0 }.map { PositionRow(it.key, it.value, PRICE) },
        )

    override fun openOrders() = book.orders.values.filter { it.status == OrderStatus.WORKING }

    override fun place(order: NewOrder): VenueOrder {
        if (order.quantity < STEP || order.quantity.remainder(STEP).signum() != 0) {
            throw VenueRefusedException("${order.quantity} is off the volume step")
        }
        val now = System.currentTimeMillis()
        val rests = order.type != OrderType.MARKET || marketRests
        val filled = if (rests) BigDecimal.ZERO else order.quantity.min(if (order.reduceOnly) closeDepth else depth)
        val status =
            when {
                rests -> OrderStatus.WORKING
                filled.compareTo(order.quantity) == 0 -> OrderStatus.FILLED
                else -> OrderStatus.CANCELLED
            }
        val placed =
            VenueOrder(
                order.clientOrderId,
                "v-${book.orders.size + 1}",
                order.symbol,
                order.side,
                order.type,
                order.quantity,
                order.limitPrice,
                order.stopPrice,
                order.timeInForce,
                order.reduceOnly,
                status,
                filled,
                PRICE.takeIf { filled.signum() > 0 },
                null,
                now,
                now,
            )
        book.orders[order.clientOrderId] = placed
        if (filled.signum() > 0) fill(placed, filled, now)
        listener?.order(placed)
        return placed
    }

    private fun fill(
        order: VenueOrder,
        quantity: BigDecimal,
        now: Long,
    ) {
        val id = "fill-${book.fillIds.incrementAndGet()}"
        val fill = VenueFill(order.clientOrderId, null, id, order.symbol, order.side, quantity, PRICE, now)
        book.fills += fill
        book.net.merge(order.symbol, if (order.side == Side.BUY) quantity else quantity.negate(), BigDecimal::add)
        listener?.fill(fill)
    }

    override fun cancel(clientOrderId: String) {
        book.orders.computeIfPresent(clientOrderId) { _, o ->
            if (o.status == OrderStatus.WORKING) o.copy(status = OrderStatus.CANCELLED) else o
        }
    }

    override fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): Boolean {
        val order = book.orders[clientOrderId]?.takeIf { it.status == OrderStatus.WORKING } ?: return false
        book.orders[clientOrderId] = order.copy(limitPrice = change.limitPrice ?: order.limitPrice)
        return true
    }

    override fun orderByLabel(clientOrderId: String): VenueOrder? = book.orders[clientOrderId]

    override fun fills(
        fromMs: Long,
        toMs: Long,
    ) = book.fills.filter { it.timeMs in fromMs..toMs }

    override fun klines(
        code: String,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar> {
        val first = (fromMs + resolutionMs - 1) / resolutionMs * resolutionMs
        return generateSequence(first) { it + resolutionMs }
            .takeWhile { it < toMs }
            .take(3)
            .map { VenueBar(it, PRICE, BigDecimal("101"), BigDecimal("99"), BigDecimal("100.5"), BigDecimal("2")) }
            .toList()
    }

    override fun subscribeQuotes(codes: Set<String>) {
        val now = System.currentTimeMillis()
        codes.forEach { code ->
            val quote =
                if (code == OPTION) {
                    VenueQuote(code, markIv = BigDecimal("55"), underlying = PRICE, timeMs = now)
                } else {
                    VenueQuote(code, bid = BigDecimal("99.5"), ask = BigDecimal("100.5"), timeMs = now)
                }
            listener?.quote(quote)
        }
    }

    override fun close() = Unit

    private companion object {
        const val MINUTE = 60_000L
        const val EXPIRY = 1_893_456_000_000L
    }
}

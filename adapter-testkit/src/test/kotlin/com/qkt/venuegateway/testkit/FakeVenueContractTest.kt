package com.qkt.venuegateway.testkit

import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueAdapter
import java.math.BigDecimal
import java.nio.file.Path

/** The orders a contract run on [FakeVenue] places: one account per state directory, so a restart finds its orders. */
abstract class FakeVenueContract : AdapterContractTest() {
    private val books = HashMap<Path, FakeBook>()
    private val farBid = BigDecimal("50")

    internal open fun configure(venue: FakeVenue) = Unit

    override fun newAdapter(stateDir: Path): VenueAdapter =
        FakeVenue(books.getOrPut(stateDir) { FakeBook() }).also(::configure)

    override fun restingOrder(clientOrderId: String) =
        fillingOrder(clientOrderId).copy(type = OrderType.LIMIT, limitPrice = farBid, timeInForce = TimeInForce.GTC)

    override fun fillingOrder(clientOrderId: String) =
        NewOrder(clientOrderId, PERP, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.IOC)

    override fun closingOrder(clientOrderId: String) =
        fillingOrder(clientOrderId).copy(side = Side.SELL, reduceOnly = true)

    override fun refusedOrder(clientOrderId: String) = restingOrder(clientOrderId).copy(quantity = BigDecimal("0.0001"))

    override val activeCode = PERP
    override val pushTimeoutMs = 2_000L
}

/** A venue serving every optional capability passes the whole contract, its overrun ending with what the book held. */
class FakeVenueContractTest : FakeVenueContract() {
    override fun configure(venue: FakeVenue) {
        venue.depth = BigDecimal("2")
    }

    override fun overrunOrder(clientOrderId: String) = fillingOrder(clientOrderId).copy(quantity = BigDecimal("5"))

    override val perpetualCode = PERP
    override val optionCode = OPTION
    override val barWindowMs = 300_000L
}

/** A venue serving nothing optional, changing no order and filling every market order whole passes it too. */
class BareFakeVenueContractTest : FakeVenueContract() {
    override fun configure(venue: FakeVenue) {
        venue.capabilities = emptySet()
    }

    override val modifies = false
}

package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.DeribitMarketData
import com.qkt.venuegateway.deribit.DeribitTicker
import com.qkt.venuegateway.deribit.DeribitTickers
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * A venue-free account trading against Deribit's live public prices: listings from [market], tickers
 * from the feed [tickers] builds, matching and money in a [PaperBook], kept across restarts by a
 * [PaperStore]. Orders and fills reach the listener on one ordered thread after the call that made
 * them returns, as a venue acknowledges an order before it reports the fill. Expired contracts settle
 * through [PaperSettlement]. Settings: `currency` (USDC), `starting_balance` (10000), `fee_rate` (0),
 * `login` (paper), `settlement_check_ms` (60000). It holds no margin: margin used is 0 and the whole equity is available.
 */
class PaperAdapter(
    private val context: AdapterContext,
    private val market: DeribitMarketData,
    private val tickers: (onTicker: (DeribitTicker) -> Unit, onConnection: (Boolean, String) -> Unit) -> DeribitTickers,
) : VenueAdapter {
    override val id = "paper"
    override val version = "0.1.0"
    private val currency = context.settings["currency"] ?: "USDC"
    private val listing = PaperListing(market, currency, context.clock)
    private val book =
        PaperBook(
            PaperLedger(BigDecimal(context.settings["starting_balance"] ?: "10000")),
            currency,
            BigDecimal(context.settings["fee_rate"] ?: "0"),
        )
    private val store = PaperStore(context.stateDir).also { it.load(book) }
    private val events = Executors.newSingleThreadExecutor { r -> Thread(r, "paper-events").apply { isDaemon = true } }
    private val settlement =
        PaperSettlement(
            market,
            listing,
            context.clock,
            context.settings["settlement_check_ms"]?.toLong() ?: SETTLEMENT_CHECK_MS,
        )
    private val feed =
        PaperFeed(listing) {
            synchronized(book) {
                book.state.orders.values
                    .filter { it.status == OrderStatus.WORKING }
                    .map { it.symbol } +
                    book.ledger.positions.keys
            }
        }
    private var listener: AdapterListener? = null

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        feed.start(tickers(::onTicker, listener::connection))
        settlement.start { settleExpired() }
    }

    override fun identity() = VenueIdentity(context.settings["login"] ?: "paper", TradeMode.DEMO, currency)

    override fun instruments() = venue { listing.all().map(listing::neutral) }

    override fun account(): AccountSnapshot =
        synchronized(book) {
            val equity = book.ledger.equity { feed[it]?.mark }
            AccountSnapshot(currency, book.ledger.balance, equity, BigDecimal.ZERO, equity)
        }

    override fun positions() =
        synchronized(book) {
            Positions(
                Accounting.NETTING,
                book.ledger.positions.map { (s, p) ->
                    PositionRow(s, p.quantity, p.avgPrice)
                },
            )
        }

    override fun openOrders() =
        synchronized(book) {
            book.state.orders.values
                .filter { it.status == OrderStatus.WORKING }
        }

    override fun place(order: NewOrder): VenueOrder {
        if (listing.find(order.symbol) == null) throw VenueRefusedException("${order.symbol} is not listed")
        val ticker = feed[order.symbol] ?: venue { market.ticker(order.symbol) }.also { feed.heard(it) }
        val placed =
            synchronized(book) {
                val change = book.place(order, ticker, context.clock())
                val placed = book.state.orders.getValue(order.clientOrderId)
                if (placed.status == OrderStatus.REJECTED) {
                    store.save(book)
                    throw VenueRefusedException(placed.rejectReason ?: "rejected")
                }
                publish(change)
                placed
            }
        feed.resubscribe()
        return placed
    }

    override fun cancel(clientOrderId: String) =
        synchronized(book) {
            book.cancel(clientOrderId, context.clock()).also { store.save(book) }
        }

    override fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): VenueOrder = throw VenueRefusedException("the paper venue does not modify orders; cancel and place again")

    override fun orderByLabel(clientOrderId: String) = synchronized(book) { book.state.orders[clientOrderId] }

    override fun fills(
        fromMs: Long,
        toMs: Long,
    ) = synchronized(book) { book.state.fills.filter { it.timeMs in fromMs..toMs } }

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ) = synchronized(book) { book.state.settlements.filter { it.timeMs in fromMs..toMs } }

    override fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar> =
        venue { market.klines(code, windowMs / MINUTE_MS, fromMs, toMs) }
            .map { VenueBar(it.startMs, it.open, it.high, it.low, it.close, it.volume) }

    override fun subscribeQuotes(
        codes: Set<String>,
        roots: Set<String>,
    ) = venue { feed.want(codes, roots) }

    override fun close() {
        settlement.close()
        feed.close()
        events.shutdown()
    }

    private fun onTicker(ticker: DeribitTicker) {
        val quote = feed.heard(ticker)
        events.execute { listener?.quote(quote) }
        synchronized(book) { publish(book.onTicker(ticker, context.clock())) }
    }

    private fun settleExpired() {
        val settled = synchronized(book) { settlement.due(book).also { if (it.isNotEmpty()) store.save(book) } }
        settled.forEach { s -> events.execute { listener?.settlement(s) } }
    }

    /** Saves and delivers what [change] made; called under the book's lock so deliveries keep its order. */
    private fun publish(change: PaperChange) {
        if (change.orders.isEmpty() && change.fills.isEmpty()) return
        store.save(book)
        change.orders.forEach { o -> events.execute { listener?.order(o) } }
        change.fills.forEach { f -> events.execute { listener?.fill(f) } }
    }

    private fun <T> venue(call: () -> T): T =
        try {
            call()
        } catch (e: IOException) {
            throw VenueUnavailableException("deribit: ${e.message}", e)
        }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val SETTLEMENT_CHECK_MS = 60_000L
    }
}

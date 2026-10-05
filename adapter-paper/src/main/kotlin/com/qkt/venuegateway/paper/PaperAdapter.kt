package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.DeribitBars
import com.qkt.venuegateway.deribit.DeribitDepth
import com.qkt.venuegateway.deribit.DeribitListing
import com.qkt.venuegateway.deribit.DeribitMarketMapping
import com.qkt.venuegateway.deribit.DeribitMarks
import com.qkt.venuegateway.deribit.DeribitOpenInterest
import com.qkt.venuegateway.deribit.DeribitTape
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.util.concurrent.Executors

/**
 * A venue-free account trading against Deribit's live public prices: listings from [market], tickers
 * from the feed [tickers] builds, matching and money in a [PaperBook], kept across restarts by a
 * [PaperStore]. Orders and fills reach the listener on one ordered thread after the call that made
 * them returns, as a venue acknowledges an order before it reports the fill. Expired contracts settle
 * through [PaperSettlement] and held perpetuals pay Deribit's published funding through [PaperFunding], both
 * checked every `settlement_check_ms`. Settings: `currency` (USDC), `starting_balance` (10000), `fee_rate`
 * (0), `login` (paper), `settlement_check_ms` (60000). It holds no margin: margin used is 0 and the whole
 * equity is available. Marks are Deribit's, from the trade history on [history] ([DeribitMarks]).
 * Open interest and the order book are Deribit's, recorded as they are read ([DeribitOpenInterest], [DeribitDepth]).
 * The tape and liquidations are Deribit's, from the trade history on [history] ([DeribitTape]).
 */
class PaperAdapter(
    private val context: AdapterContext,
    private val market: DeribitMarketData,
    private val history: DeribitMarketData = market,
    private val tickers: (onTicker: (DeribitTicker) -> Unit, onConnection: (Boolean, String) -> Unit) -> DeribitTickers,
) : VenueAdapter {
    override val id = "paper"
    override val version: String = javaClass.`package`?.implementationVersion ?: "dev"
    override val capabilities = Capability.entries.toSet()
    private val currency = context.settings["currency"] ?: "USDC"
    private val listing = DeribitListing(market, currency, context.clock)
    private val book = paperBook(context.settings, currency)
    private val store = PaperStore(context.stateDir).also { it.load(book) }
    private val events = Executors.newSingleThreadExecutor { r -> Thread(r, "paper-events").apply { isDaemon = true } }
    private val funding = PaperFunding(market, listing, currency, context.clock)
    private val checkMs = context.settings["settlement_check_ms"]?.toLong() ?: CHECK_MS
    private val openInterest = DeribitOpenInterest({ venue { market.ticker(it) } }, context.stateDir, context.clock)
    private val depth = DeribitDepth(market::orderBook, context.stateDir, context.clock)
    private val upkeep =
        PaperUpkeep(
            book,
            store,
            PaperSettlement(market, listing, context.clock, checkMs),
            funding,
            events::execute,
        ) { listener }
    private val feed = PaperFeed(listing) { synchronized(book) { book.quoted() } }
    private var listener: AdapterListener? = null

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        feed.start(tickers(::onTicker, listener::connection))
        upkeep.start()
    }

    override fun identity() = VenueIdentity(context.settings["login"] ?: "paper", TradeMode.DEMO, currency)

    override fun instruments() = venue { listing.all().map(DeribitMarketMapping::instrument) }

    override fun account(): AccountSnapshot = synchronized(book) { book.account { feed[it]?.mark } }

    override fun positions() = synchronized(book) { book.positionRows() }

    override fun openOrders() = synchronized(book) { book.working() }

    override fun place(order: NewOrder): VenueOrder {
        if (listing.find(order.symbol) == null) throw VenueRefusedException("${order.symbol} is not listed")
        val ticker = feed[order.symbol] ?: venue { market.ticker(order.symbol) }.also { feed.heard(it) }
        val placed = synchronized(book) { book.placeChecked(order, ticker, context.clock(), store, ::publish) }
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

    override fun funding(
        fromMs: Long,
        toMs: Long,
    ) = synchronized(book) { book.funding.records.filter { it.timeMs in fromMs..toMs } }

    override fun fundingRates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = venue { funding.rates(code, fromMs, toMs) }

    override fun marks(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueMark> = venue { DeribitMarks.sampled(history, code, windowMs, fromMs, toMs) }

    override fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = openInterest.read(code, fromMs, toMs)

    override fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ): List<VenuePrint> = venue { DeribitTape.prints(history, code, fromMs, toMs, limit) }

    override fun liquidations(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenuePrint> = venue { DeribitTape.liquidations(history, code, fromMs, toMs) }

    override fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = depth.read(code, fromMs, toMs)

    override fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar> = venue { DeribitBars.closed(market, code, windowMs, fromMs, toMs, context.clock()) }

    override fun subscribeQuotes(
        codes: Set<String>,
        roots: Set<String>,
    ) = venue { feed.want(codes, roots) }

    override fun close() {
        upkeep.close()
        feed.close()
        events.shutdown()
    }

    private fun onTicker(ticker: DeribitTicker) {
        val quote = feed.heard(ticker)
        events.execute { listener?.quote(quote) }
        synchronized(book) { publish(book.onTicker(ticker, context.clock())) }
    }

    /** Saves and delivers what [change] made; called under the book's lock so deliveries keep its order. */
    private fun publish(change: PaperChange) {
        if (change.orders.isEmpty() && change.fills.isEmpty()) return
        store.save(book)
        change.orders.forEach { o -> events.execute { listener?.order(o) } }
        change.fills.forEach { f -> events.execute { listener?.fill(f) } }
    }

    private companion object {
        const val CHECK_MS = 60_000L
    }
}

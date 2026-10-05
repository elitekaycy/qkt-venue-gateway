package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOpenInterest
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenuePrint
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.DeribitTrading

/**
 * One Deribit account (settings: [DeribitSettings]; credentials: the API key's client id as login and
 * its secret). Orders carry the client's id as Deribit's label, so every lookup is by label and a
 * stop that fires (a new Deribit order) is still found. The account's link ([trading]) is the venue
 * connection the host watches; the public ticker link ([tickers]) only feeds quotes, so its drops are
 * reported as the quote feed going down, not the venue. An order on a code the account's listing does not
 * hold (another currency's contract, a spot pair) is refused before it reaches Deribit ([DeribitOrderDesk]). Root subscriptions re-expand every [refreshMs].
 * Deribit holds one net position per instrument. Deribit refuses IOC and FOK on market and stop orders
 * (only limit types take them); that refusal is passed back as it is, never remapped to another time
 * in force. Marks, the tape and liquidations come from the trade history on [history] ([DeribitMarks],
 * [DeribitTape]; by default [market]). Depth and open interest are recorded ([DeribitDepth],
 * [DeribitOpenInterest]) and pruned to their retention on the refresh timer, from [connect] on.
 */
class DeribitAdapter(
    private val context: AdapterContext,
    private val market: DeribitMarketData,
    trading: (
        onOrder: (DeribitOrder) -> Unit,
        onTrade: (DeribitTrade) -> Unit,
        onConnection: (Boolean, String) -> Unit,
    ) -> DeribitTrading,
    tickers: (onTicker: (DeribitTicker) -> Unit, onConnection: (Boolean, String) -> Unit) -> DeribitTickers,
    private val refreshMs: Long = 600_000,
    private val history: DeribitMarketData = market,
) : VenueAdapter {
    override val id = "deribit"
    override val version: String = javaClass.`package`?.implementationVersion ?: "dev"

    override val capabilities =
        setOf(Capability.BARS, Capability.QUOTES, Capability.SETTLEMENTS, Capability.FUNDING, Capability.FUNDING_RATES)
            .plus(setOf(Capability.MARK_PRICES, Capability.OPEN_INTEREST, Capability.OPTION_MARKS))
            .plus(setOf(Capability.TRADES, Capability.LIQUIDATIONS, Capability.DEPTH))
    private val settings = DeribitSettings.of(context.settings)
    private val login = context.requiredCredentials().login
    private val currency = settings.currency
    private val listing = DeribitListing(market, currency, context.clock, refreshMs)

    @Volatile private var listener: AdapterListener? = null
    private val account =
        trading(
            { order -> DeribitMapping.order(order)?.let { listener?.order(it) } },
            { trade -> DeribitMapping.fill(trade)?.let { listener?.fill(it) } },
        ) { up, reason -> listener?.connection(up, reason) }
    private val accountHistory = DeribitAccountHistory(account, currency, listing)
    private val orders = DeribitOrderDesk(account, currency, listing, settings.stopTrigger)
    private val feed =
        DeribitQuoteFeed(
            listing,
            tickers({
                listener?.quote(DeribitMarketMapping.quote(it))
            }) { up, reason -> listener?.quoteFeed(up, reason) },
            ::prune,
        )
    private val retention = settings.retention
    private val openInterest =
        DeribitOpenInterest(
            { venue { market.ticker(it) } },
            context.stateDir,
            context.clock,
            retention.openInterestDays,
        )
    private val depth = DeribitDepth(market::orderBook, context.stateDir, context.clock, retention.depthDays)

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        account.start()
        feed.start(refreshMs)
    }

    override fun identity() = VenueIdentity(login, settings.environment.mode, currency)

    override fun instruments() = venue { listing.all().map(DeribitMarketMapping::instrument) }

    override fun account(): AccountSnapshot = DeribitMapping.account(venue { account.account(currency) })

    override fun positions() =
        Positions(Accounting.NETTING, venue { account.positions(currency) }.mapNotNull(DeribitMapping::position))

    override fun openOrders() = venue { account.openOrders(currency) }.mapNotNull(DeribitMapping::order)

    override fun place(order: NewOrder): VenueOrder = orders.place(order)

    override fun cancel(clientOrderId: String) = orders.cancel(clientOrderId)

    override fun modify(
        clientOrderId: String,
        change: OrderChange,
    ) = orders.modify(clientOrderId, change)

    override fun orderByLabel(clientOrderId: String) = orders.byLabel(clientOrderId)

    override fun fills(
        fromMs: Long,
        toMs: Long,
    ) = venue { account.trades(currency, fromMs, toMs) }.mapNotNull(DeribitMapping::fill)

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement> = accountHistory.settlements(fromMs, toMs)

    override fun funding(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFunding> = accountHistory.funding(fromMs, toMs)

    override fun fundingRates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ): List<VenueFundingRate> = venue { market.fundingRates(code, fromMs, toMs) }.map(DeribitMarketMapping::fundingRate)

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
    ): List<VenueOpenInterest> = openInterest.read(code, fromMs, toMs)

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
    ): List<VenueDepth> = depth.read(code, fromMs, toMs)

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

    /** Prunes the recordings past their retention, at most once a UTC day. */
    private fun prune() {
        depth.prune()
        openInterest.prune()
    }

    override fun close() {
        feed.close()
        account.close()
    }
}

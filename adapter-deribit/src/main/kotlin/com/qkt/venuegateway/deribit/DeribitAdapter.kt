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
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueFundingRate
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueMark
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
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
 * hold (another currency's contract, a spot pair) is refused before it reaches Deribit. Root subscriptions re-expand every [refreshMs].
 * Deribit holds one net position per instrument. Deribit refuses IOC and FOK on market and stop orders
 * (only limit types take them); that refusal is passed back as it is, never remapped to another time
 * in force. Marks come from the trade history on [history] ([DeribitMarks]; by default [market]).
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
            .plus(Capability.MARK_PRICES)
    private val settings = DeribitSettings.of(context.settings)
    private val login = context.requiredCredentials().login
    private val currency = settings.currency
    private val listing = DeribitListing(market, currency, context.clock, refreshMs)

    @Volatile private var listener: AdapterListener? = null
    private val account = trading(::onOrder, ::onTrade) { up, reason -> listener?.connection(up, reason) }
    private val feed =
        DeribitQuoteFeed(
            listing,
            tickers({
                listener?.quote(DeribitMarketMapping.quote(it))
            }) { up, reason -> listener?.quoteFeed(up, reason) },
        )

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        account.start()
        feed.start(refreshMs)
    }

    override fun identity() = VenueIdentity(login, settings.environment.mode, currency)

    override fun instruments() = venue { listing.all().map(DeribitMarketMapping::instrument) }

    override fun account(): AccountSnapshot = DeribitMapping.account(venue { account.account(currency) })

    override fun positions() =
        Positions(
            Accounting.NETTING,
            venue {
                account.positions(currency)
            }.mapNotNull(DeribitMapping::position),
        )

    override fun openOrders() = venue { account.openOrders(currency) }.mapNotNull(DeribitMapping::order)

    /** Only a listed instrument of the account's currency: any other would be traded outside every read. */
    override fun place(order: NewOrder): VenueOrder {
        if (venue { listing.find(order.symbol) } == null) {
            throw VenueRefusedException("${order.symbol} is not a listed $currency contract of this account")
        }
        val placed = venue { account.place(DeribitMapping.newOrder(order, settings.stopTrigger)) }
        return DeribitMapping.order(placed) ?: error("deribit answered order ${order.clientOrderId} without its label")
    }

    /** Cancels the order labelled [clientOrderId] and returns it as it now stands (filled, if it filled first). */
    override fun cancel(clientOrderId: String): VenueOrder? {
        venue { account.cancelByLabel(currency, clientOrderId) }
        return orderByLabel(clientOrderId)
    }

    override fun modify(
        clientOrderId: String,
        change: OrderChange,
    ): VenueOrder? {
        val current = orderByLabel(clientOrderId) ?: return null
        val quantity = change.quantity ?: current.quantity
        val edited =
            venue { account.editByLabel(clientOrderId, current.symbol, quantity, change.limitPrice, change.stopPrice) }
        return DeribitMapping.order(edited)
    }

    /** The newest Deribit order labelled [clientOrderId] (a fired stop is a newer order than the stop). */
    override fun orderByLabel(clientOrderId: String) =
        venue {
            account.ordersByLabel(
                currency,
                clientOrderId,
            )
        }.maxByOrNull { it.updatedMs }?.let(DeribitMapping::order)

    override fun fills(
        fromMs: Long,
        toMs: Long,
    ) = venue { account.trades(currency, fromMs, toMs) }.mapNotNull(DeribitMapping::fill)

    /** Deliveries and exercises at the price each unit settled at ([DeribitSettlementMapping]); expired codes looked up one by one. */
    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement> =
        venue {
            val rows = account.settlements(currency, fromMs, toMs)
            DeribitSettlementMapping.settlements(rows, listing::held) { account.transactions(currency, fromMs, toMs) }
        }

    /** The funding the transaction log shows realized on perpetuals; Deribit pushes none, the host reconciles it. */
    override fun funding(
        fromMs: Long,
        toMs: Long,
    ): List<VenueFunding> =
        venue { account.transactions(currency, fromMs, toMs) }.mapNotNull { row ->
            DeribitMapping.funding(row) { name -> venue { listing.held(name) }.perpetual }
        }

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
        feed.close()
        account.close()
    }

    private fun onOrder(order: DeribitOrder) {
        DeribitMapping.order(order)?.let { listener?.order(it) }
    }

    private fun onTrade(trade: DeribitTrade) {
        DeribitMapping.fill(trade)?.let { listener?.fill(it) }
    }
}

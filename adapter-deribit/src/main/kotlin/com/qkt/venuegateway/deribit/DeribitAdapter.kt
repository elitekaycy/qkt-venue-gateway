package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.adapter.VenueUnavailableException
import com.qkt.venuegateway.deribit.DeribitErrors.venue
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import com.qkt.venuegateway.deribit.client.DeribitTrade
import com.qkt.venuegateway.deribit.client.DeribitTrading
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * One Deribit account (settings: [DeribitSettings]; credentials: the API key's client id as login and
 * its secret). Orders carry the client's id as Deribit's label, so every lookup is by label and a
 * stop that fires (a new Deribit order) is still found. The account's link ([trading]) is the venue
 * connection the host watches; the public ticker link ([tickers]) only feeds quotes, so its drops are
 * logged, never reported as the venue going down. Root subscriptions re-expand every [refreshMs].
 * Deribit holds one net position per instrument. Deribit refuses IOC and FOK on market and stop orders
 * (only limit types take them); that refusal is passed back as it is, never remapped to another time
 * in force.
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
) : VenueAdapter {
    override val id = "deribit"
    override val version: String = javaClass.`package`?.implementationVersion ?: "dev"
    private val log = LoggerFactory.getLogger(DeribitAdapter::class.java)
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
            }) { up, reason -> log.info("deribit tickers up={}: {}", up, reason) },
        )
    private val timer =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "deribit-refresh").apply {
                isDaemon =
                    true
            }
        }

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        account.start()
        feed.start()
        timer.scheduleWithFixedDelay(::refreshQuotes, refreshMs, refreshMs, TimeUnit.MILLISECONDS)
    }

    override fun identity() = VenueIdentity(login, settings.environment.mode, currency)

    override fun instruments() = venue { listing.all().map(DeribitMarketMapping::instrument) }

    override fun account(): AccountSnapshot {
        val a = venue { account.account(currency) }
        return AccountSnapshot(
            a.currency,
            a.balance,
            a.equity,
            a.initialMargin,
            a.availableFunds,
            a.initialMargin,
            a.maintenanceMargin,
        )
    }

    override fun positions() =
        Positions(
            Accounting.NETTING,
            venue {
                account.positions(currency)
            }.mapNotNull(DeribitMapping::position),
        )

    override fun openOrders() = venue { account.openOrders(currency) }.mapNotNull(DeribitMapping::order)

    override fun place(order: NewOrder): VenueOrder {
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

    /** Not mapped until a delivery is recorded from the venue; refused as unavailable rather than invented. */
    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement> = throw VenueUnavailableException("deribit settlements are not mapped yet")

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
        timer.shutdownNow()
        feed.close()
        account.close()
    }

    private fun refreshQuotes() {
        runCatching { venue { feed.refresh() } }.onFailure { log.warn("deribit quote refresh failed: {}", it.message) }
    }

    private fun onOrder(order: DeribitOrder) {
        DeribitMapping.order(order)?.let { listener?.order(it) }
    }

    private fun onTrade(trade: DeribitTrade) {
        DeribitMapping.fill(trade)?.let { listener?.fill(it) }
    }
}

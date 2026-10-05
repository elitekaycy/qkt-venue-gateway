package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import org.slf4j.LoggerFactory

/**
 * One Bybit unified account, one category of it (settings: [BybitSettings]; credentials: the API key as login
 * and its secret). Orders carry the client's id as Bybit's `orderLinkId`, so every lookup is by it. The account
 * socket ([account]) is the venue connection the host watches; the public socket ([market]) feeds quotes, the
 * tape and liquidations, so its drops are the quote feed's. Fills are `Trade` executions and funding is `Funding`
 * executions ([BybitMapping]), both read back from the execution list by the host's reconcile. Contracts declare
 * funding, funding rates, marks and open interest from Bybit's own histories; the tape, liquidations and depth
 * are recorded ([BybitTape], [BybitDepth]). Expiry settlements are not served: none has yet been recorded from
 * Bybit to map from.
 */
class BybitAdapter(
    private val context: AdapterContext,
    private val public: BybitPublicClient,
    private val private: BybitPrivateClient,
    account: AccountLink,
    market: MarketLink,
    refreshMs: Long = 600_000,
) : VenueAdapter {
    override val id = "bybit"
    override val version: String = javaClass.`package`?.implementationVersion ?: "dev"
    private val settings = BybitSettings.of(context.settings)
    private val log = LoggerFactory.getLogger(BybitAdapter::class.java)

    override val capabilities = settings.capabilities
    private val category = settings.category
    private val currency = settings.currency
    private val login = context.requiredCredentials().login
    private val listing = BybitListing({ public.instruments(category) }, currency, context.clock, refreshMs)
    private val modes =
        BybitPositionModes(private::positionSlots, ::reference, settings.contracts, context.clock, refreshMs)
    private val orders = BybitOrderDesk(private, settings, listing, modes)

    @Volatile private var listener: AdapterListener? = null
    private val accountStream =
        account(
            { o -> BybitMapping.order(o)?.let { listener?.order(it) } },
            { e ->
                BybitMapping.fill(e, currency)?.let { listener?.fill(it) }
                BybitMapping.funding(e, currency)?.let { listener?.funding(it) }
            },
        ) { up, reason -> listener?.connection(up, reason) }
    private val marketStream =
        market(
            { listener?.quote(BybitMarketMapping.quote(it)) },
            { code, prints -> history.tape.onTrades(code, prints) },
            { code, prints -> history.tape.onLiquidations(code, prints) },
        ) { up, reason -> listener?.quoteFeed(up, reason) }
    private val history: BybitMarket = BybitMarket(public, settings, listing, context) { marketStream.tape(it) }

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        accountStream.start()
        marketStream.start()
        runCatching { history.tape.resume() }.onFailure { log.warn("bybit tape not resumed: {}", it.message) }
    }

    override fun identity() = VenueIdentity(login, settings.environment.mode, currency)

    override fun instruments() = venue { listing.all() }.map(BybitMarketMapping::instrument)

    override fun account(): AccountSnapshot = BybitMapping.account(venue { private.wallet(currency) })

    override fun positions(): Positions {
        if (!settings.contracts) {
            throw VenueRefusedException(
                "a bybit spot account holds coins, not positions: see the account",
            )
        }
        val rows = venue { private.positions() }.mapNotNull(BybitMapping::position)
        return Positions(modes.accounting(rows), rows)
    }

    override fun openOrders() = orders.open()

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
    ) = venue { private.executions(fromMs, toMs) }.mapNotNull { BybitMapping.fill(it, currency) }

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement> = throw VenueUnsupportedException("settlements")

    override fun funding(
        fromMs: Long,
        toMs: Long,
    ) = contract("funding") {
        venue { private.executions(fromMs, toMs, "Funding") }.mapNotNull { BybitMapping.funding(it, currency) }
    }

    override fun fundingRates(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = contract("funding rates") { history.fundingRates(code, fromMs, toMs) }

    override fun marks(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = contract("mark prices") { history.marks(code, windowMs, fromMs, toMs) }

    override fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = contract("open interest") { history.openInterest(code, fromMs, toMs) }

    override fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ) = history.trades(code, fromMs, toMs, limit)

    override fun liquidations(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = contract("liquidations") { history.liquidations(code, fromMs, toMs) }

    override fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = history.depth(code, fromMs, toMs)

    override fun bars(
        code: String,
        windowMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = history.bars(code, windowMs, fromMs, toMs)

    /** Quotes the listed [codes]; Bybit lists no options on these categories, so [roots] name nothing to quote. */
    override fun subscribeQuotes(
        codes: Set<String>,
        roots: Set<String>,
    ) = marketStream.quote(history.quotable(codes + roots))

    override fun close() {
        marketStream.close()
        accountStream.close()
    }

    private fun <T> contract(
        what: String,
        call: () -> T,
    ): T = if (settings.contracts) call() else throw VenueUnsupportedException(what)

    /** The coin's BTC perpetual, whose position mode a switch by coin sets for every contract of the coin. */
    private fun reference(): String? =
        listing
            .all()
            .firstOrNull {
                it.baseCoin == "BTC" &&
                    it.contractType == "LinearPerpetual"
            }?.symbol
}

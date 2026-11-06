package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AccountSnapshot
import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.kalshi.KalshiErrors.venue
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import com.qkt.venuegateway.kalshi.client.KalshiPortfolio
import com.qkt.venuegateway.kalshi.client.KalshiPortfolioJson
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

/** What the adapter hears on Kalshi's socket: fills, orders and tickers as each push's `msg`, and the link's state. */
internal fun interface KalshiLink {
    fun open(
        onFill: (JsonObject) -> Unit,
        onOrder: (JsonObject) -> Unit,
        onTicker: (JsonObject) -> Unit,
        onConnection: (Boolean, String) -> Unit,
    ): KalshiStream
}

/** The socket as the adapter drives it. */
internal interface KalshiStream : AutoCloseable {
    fun start()

    fun quote(markets: List<String>)
}

/**
 * One Kalshi account, one shard of it ([KalshiSettings]; credentials: the API key id as login and its PEM private key
 * as secret). Every market of every category is listed as an event contract ([KalshiListing]); orders carry the
 * client's id as Kalshi's `client_order_id` ([KalshiOrderDesk]). The socket ([KalshiStream]) is the venue
 * connection: it pushes the account's fills and orders and the quoted markets' tickers. Fills and settlements are
 * also read back from the account's history by the host's reconcile.
 */
internal class KalshiAdapter(
    private val context: AdapterContext,
    private val settings: KalshiSettings,
    private val public: KalshiPublic,
    private val portfolio: KalshiPortfolio,
    link: KalshiLink,
    private val listing: KalshiListing = KalshiListing(public, settings.shard, context.clock),
) : VenueAdapter {
    override val id = "kalshi"
    override val version: String = javaClass.`package`?.implementationVersion ?: "dev"
    override val capabilities =
        setOf(
            Capability.BARS,
            Capability.QUOTES,
            Capability.SETTLEMENTS,
            Capability.TRADES,
            Capability.DEPTH,
            Capability.OPEN_INTEREST,
        )
    override val klineResolutions = KalshiMarketData.PERIODS.keys

    /** Kalshi's full listing is some 35 slow pages, so the host reads it every 5 minutes. */
    override val listingRefreshMs = 300_000L

    private val login = context.requiredCredentials().login
    private val labels = KalshiLabels(context.stateDir)
    private val orders = KalshiOrderDesk(portfolio, listing, labels, ::net)
    private val market = KalshiMarketData(public, listing, context)

    @Volatile private var listener: AdapterListener? = null

    @Volatile private var quoted: Set<String> = emptySet()

    private val stream =
        link.open(
            { f ->
                val fill = KalshiPortfolioJson.fill(f)
                val label = fill.clientOrderId ?: labels.label(fill.orderId) ?: fill.orderId
                listener?.fill(KalshiMarketMapping.fill(fill, label))
            },
            { o ->
                val order = KalshiPortfolioJson.order(o)
                orders.learn(order)
                listener?.order(orders.mapped(order))
            },
            { t -> listener?.quote(KalshiMarketMapping.quote(KalshiMarketJson.ticker(t))) },
        ) { up, reason -> listener?.connection(up, reason) }

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        stream.start()
    }

    override fun identity() = VenueIdentity(login, settings.environment.mode, USD)

    override fun instruments() = venue { listing.all() }.map { it.instrument }.sortedBy { it.code }

    /** The shard's cash as balance and margin available; positions at Kalshi's portfolio value as margin used. */
    override fun account(): AccountSnapshot {
        val balance = venue { portfolio.balance() }
        val cash = balance.byShard[settings.shard] ?: BigDecimal.ZERO
        val held = BigDecimal(balance.portfolioValueCents).movePointLeft(2)
        return AccountSnapshot(USD, cash, cash + held, held, cash)
    }

    /** This shard's positions, one net row a market. */
    override fun positions(): Positions {
        val rows =
            venue { portfolio.positions() }
                .filter { it.exchangeIndex == null || it.exchangeIndex == settings.shard }
                .mapNotNull(KalshiMarketMapping::position)
        return Positions(Accounting.NETTING, rows)
    }

    /** [code] as listed, or as Kalshi answers it by ticker (a settled market, with its outcome); null when unknown. */
    override fun instrument(code: String) = venue { listing.instrument(code) }

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
    ) = orders.fills(fromMs, toMs)

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ) = venue { portfolio.settlements(fromMs, toMs) }
        .mapNotNull(KalshiMarketMapping::settlement)
        .filter { it.timeMs in fromMs..toMs }
        .sortedBy { it.timeMs }

    override fun openInterest(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = market.openInterest(code, fromMs, toMs)

    override fun trades(
        code: String,
        fromMs: Long,
        toMs: Long,
        limit: Int,
    ) = market.trades(code, fromMs, toMs, limit)

    override fun depth(
        code: String,
        fromMs: Long,
        toMs: Long,
    ) = market.depth(code, fromMs, toMs)

    override fun klines(
        code: String,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ) = market.klines(code, resolutionMs, fromMs, toMs)

    /** Quotes the listed [codes] (the rest logged), each newly added one first as it stands now. */
    override fun subscribeQuotes(codes: Set<String>) {
        val known = market.quotable(codes)
        val added = known.filter { it !in quoted }
        quoted = known.toSet()
        stream.quote(known)
        if (added.isNotEmpty()) market.snapshot(added).forEach { listener?.quote(it) }
    }

    override fun close() = stream.close()

    private fun net(symbol: String) =
        positions().rows.filter { it.symbol == symbol }.fold(BigDecimal.ZERO) { sum, row -> sum + row.quantity }

    private companion object {
        const val USD = "USD"
    }
}

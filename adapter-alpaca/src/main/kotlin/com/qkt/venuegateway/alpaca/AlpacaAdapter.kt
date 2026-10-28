package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.adapter.Positions
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueBar
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import com.qkt.venuegateway.alpaca.client.AlpacaData
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.JsonReads.decimal
import com.qkt.venuegateway.support.JsonReads.need
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** A link the adapter starts at [connect] and closes with it: the trade-update stream. */
interface AlpacaLink : AutoCloseable {
    fun start()
}

/**
 * One Alpaca account (settings: [AlpacaSettings]; credentials: the API key id and its secret), named by its account number,
 * trading
 * US shares and coin pairs it lists ([AlpacaListing]). Orders carry the client's id as Alpaca's
 * `client_order_id` ([AlpacaOrderDesk]); the trade-update stream ([updates]) is the venue link the host watches,
 * pushing order changes and fills; FILL activities answer the host's reconcile. Positions are netting. Quotes and
 * bars come from Alpaca's market data ([AlpacaQuotes], [AlpacaData]); Alpaca has no expiring contracts, so
 * settlements are not served.
 */
class AlpacaAdapter(
    private val settings: AlpacaSettings,
    private val trading: AlpacaTrading,
    private val data: AlpacaData,
    private val clock: () -> Long,
    updates: ((JsonObject) -> Unit, (Boolean, String) -> Unit) -> AlpacaLink,
    quotes: (
        AlpacaListing,
        (com.qkt.venuegateway.adapter.VenueQuote) -> Unit,
        (Boolean, String) -> Unit,
    ) -> AlpacaQuotes,
) : VenueAdapter {
    override val id = "alpaca"
    override val version: String = javaClass.`package`?.implementationVersion ?: "dev"
    override val capabilities = setOf(Capability.BARS, Capability.QUOTES)

    private val log = LoggerFactory.getLogger(AlpacaAdapter::class.java)
    private val listing = AlpacaListing(trading, settings.assetClasses, clock, fractional = settings.fractional)
    private val desk = AlpacaOrderDesk(trading, listing, settings, ::net)
    private val recent = AlpacaRecentFills(clock)
    private val coinFees = AlpacaCoinFees { positions().rows }

    @Volatile private var accountNumber: String? = null

    @Volatile private var listener: AdapterListener? = null
    private val stream =
        updates(::update) { up, reason ->
            // Fills while the stream was down are unknown to the kept positions: read them again before any new one.
            if (up) runCatching { coinFees.seed() }.onFailure { log.warn("alpaca positions not read: {}", it.message) }
            listener?.connection(up, reason)
        }
    private val quoteFeed = quotes(listing, { listener?.quote(it) }) { up, reason -> listener?.quoteFeed(up, reason) }

    override fun connect(listener: AdapterListener) {
        this.listener = listener
        stream.start()
    }

    /** The account is named by its Alpaca account number, never by the API key that logs in to it. */
    override fun identity(): VenueIdentity {
        val number =
            accountNumber ?: AlpacaErrors.venue { trading.account() }.need("account_number").also { accountNumber = it }
        return VenueIdentity(number, settings.environment.mode, CURRENCY)
    }

    override fun instruments() = listing.all().map { it.instrument }

    override fun account() = AlpacaMapping.account(AlpacaErrors.venue { trading.account() })

    override fun positions() =
        Positions(
            Accounting.NETTING,
            AlpacaErrors
                .venue {
                    trading.positions()
                }.map(AlpacaMapping::position),
        )

    override fun openOrders() = desk.open()

    override fun place(order: NewOrder) = desk.place(order)

    override fun cancel(clientOrderId: String) = desk.cancel(clientOrderId)

    override fun modify(
        clientOrderId: String,
        change: OrderChange,
    ) = desk.modify(clientOrderId, change)

    override fun orderByLabel(clientOrderId: String) = desk.byLabel(clientOrderId)

    override fun fills(
        fromMs: Long,
        toMs: Long,
    ) = recent.merged(desk.fills(fromMs, toMs), fromMs, toMs)

    override fun settlements(
        fromMs: Long,
        toMs: Long,
    ): List<VenueSettlement> = throw VenueUnsupportedException("settlements")

    /**
     * Minutes and hours on the UTC grid. A day is left out: Alpaca stamps a share's daily bar at midnight New York
     * (04:00 or 05:00 UTC), so the host builds days from hours instead.
     */
    override val klineResolutions = AlpacaTimeframes.RESOLUTIONS

    /** Alpaca's bars of [resolutionMs] as it reports them; the host keeps only the closed ones. */
    override fun klines(
        code: String,
        resolutionMs: Long,
        fromMs: Long,
        toMs: Long,
    ): List<VenueBar> {
        val listed = listing.require(code)
        val timeframe = AlpacaTimeframes.of(resolutionMs)
        if (toMs <= fromMs) return emptyList()
        return AlpacaErrors
            .venue { data.bars(listed.symbol, listed.crypto, timeframe, fromMs, toMs) }
            .map(AlpacaMapping::bar)
            .filter { it.startMs in fromMs until toMs }
    }

    override fun subscribeQuotes(codes: Set<String>) = quoteFeed.subscribe(codes)

    override fun close() {
        quoteFeed.close()
        stream.close()
    }

    private fun net(code: String): BigDecimal =
        positions().rows.filter { it.symbol == code }.fold(BigDecimal.ZERO) { acc, r -> acc + r.quantity }

    /** One trade update: the order as it now stands, and its execution when it filled. */
    private fun update(update: JsonObject) {
        val target = listener ?: return
        runCatching {
            AlpacaMapping.fill(update)?.let { gross ->
                val coin =
                    listing
                        .find(gross.symbol)
                        ?.takeIf { it.crypto }
                        ?.symbol
                        ?.substringBefore('/')
                val fill = coinFees.received(gross, update.decimal("position_qty"), coin)
                recent.add(fill)
                target.fill(fill)
            }
            target.order(AlpacaMapping.order(update["order"] as JsonObject))
        }.onFailure { log.error("alpaca trade update not mapped: {}", it.message) }
    }

    private companion object {
        const val CURRENCY = "USD"
    }
}

/** Alpaca's bar timeframes: minutes 1-59, hours 1-23, a day, a week. Any other length is refused by name. */
internal object AlpacaTimeframes {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** The kline lengths served: each a whole number of minutes dividing a day, on the UTC grid. */
    val RESOLUTIONS: Set<Long> = setOf(MINUTE, 5 * MINUTE, 15 * MINUTE, 30 * MINUTE, HOUR)

    fun of(windowMs: Long): String =
        when {
            windowMs % DAY == 0L && windowMs / DAY == 1L -> "1Day"
            windowMs % DAY == 0L && windowMs / DAY == 7L -> "1Week"
            windowMs % HOUR == 0L && windowMs / HOUR in 1..23 -> "${windowMs / HOUR}Hour"
            windowMs % MINUTE == 0L && windowMs / MINUTE in 1..59 -> "${windowMs / MINUTE}Min"
            else -> throw VenueRefusedException("alpaca has no bars $windowMs ms long")
        }
}

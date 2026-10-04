package com.qkt.venuegateway.host

import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.host.journal.Journal
import com.qkt.venuegateway.host.journal.appendFunding
import com.qkt.venuegateway.host.journal.killSwitch
import com.qkt.venuegateway.host.market.InstrumentShelf
import com.qkt.venuegateway.host.orders.OrderDesk
import com.qkt.venuegateway.host.orders.PositionCloser
import com.qkt.venuegateway.host.server.Role
import com.qkt.venuegateway.host.wire.WireFundingMapping
import com.qkt.venuegateway.host.wire.WireMapping
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireHealth
import com.qkt.vgp.WireInstrument
import com.qkt.vgp.WireOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory

/**
 * One venue account's gateway: the [adapter], the [journal] every event goes through, the [desk]
 * that takes orders, and who may call it ([tokens] by role). What the venue pushes is journaled here
 * (an order update that changes nothing is dropped; fills, settlements and funding are de-duplicated by
 * the journal; orders and fills the gateway never placed, such as another tool's on the same account, are
 * not the client's and are dropped) and [appended] carries the latest sequence number to every open
 * stream.
 */
class Gateway(
    val adapter: VenueAdapter,
    val journal: Journal,
    private val shelf: InstrumentShelf,
    private val tokens: Map<Role, String>,
    /** The gateway's time, epoch milliseconds. */
    val clock: () -> Long,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(Gateway::class.java)
    private val up = AtomicBoolean(false)
    private val quotesUp = AtomicBoolean(true)
    private val latest = MutableStateFlow(journal.latestSeq())

    /** Whether the venue link is up now. */
    val venueConnected: Boolean get() = up.get()

    /** Whether quotes still arrive: the venue link, and the adapter's own quote feed when it has one, are up. */
    val quotesLive: Boolean get() = up.get() && quotesUp.get()

    /** The latest journaled sequence number, as it moves. */
    val appended: StateFlow<Long> get() = latest

    /** Tells clients each position change. */
    internal val positions = PositionWatch(adapter, journal, clock)

    /** Takes orders. */
    val desk = OrderDesk(journal, adapter, up::get, clock)

    /** Flattens positions (`POST /v1/positions/close`). */
    val closer = PositionCloser(desk, adapter, clock)

    /** Hears every quote the venue pushes (the quote hub sets it). */
    @Volatile var onQuote: (VenueQuote) -> Unit = {}

    /** Hears every venue link change after it is recorded (the reconciler sets it). */
    @Volatile var onConnection: (Boolean) -> Unit = {}

    init {
        journal.onAppend { latest.value = it }
    }

    /** Opens the venue link. */
    fun start() = adapter.connect(listener)

    /** The instruments clients see: the venue's live listing and the dated contracts kept after it ([InstrumentShelf]). */
    fun instruments(): List<WireInstrument> = shelf.listing(adapter.instruments().map(WireReads::instrument))

    /** One instrument by [code], live or kept; null when neither holds it. */
    fun instrument(code: String): WireInstrument? = shelf.find(code, adapter.instruments().map(WireReads::instrument))

    /**
     * `GET /v1/orders`: the venue's working orders that this gateway placed. Another tool's orders on the
     * same account are not the client's (the stream drops them too), so they are not listed.
     */
    fun workingOrders(): List<WireOrder> =
        adapter.openOrders().filter { journal.order(it.clientOrderId) != null }.map(WireMapping::order)

    /** The role [authorization] (`Bearer <token>`) carries, or null when it carries none. */
    fun roleOf(authorization: String?): Role? {
        val token =
            authorization?.removePrefix("Bearer ")?.takeIf { authorization.startsWith("Bearer ") } ?: return null
        return tokens.entries.firstOrNull { (_, expected) -> sameBytes(expected, token) }?.key
    }

    /** `GET /v1/health`: identity, venue link, kill switch, the stream anchor and the adapter's capabilities, read together. */
    fun health(): WireHealth {
        val identity = adapter.identity()
        return WireHealth(
            protocol = "vgp1",
            adapter = adapter.id,
            adapterVersion = adapter.version,
            accountLogin = identity.login,
            tradeMode = identity.mode.name.lowercase(),
            venueConnected = up.get(),
            killSwitch = journal.killSwitch(),
            serverTime = clock(),
            stream = journal.stream,
            seq = journal.latestSeq(),
            capabilities = WireFundingMapping.capabilities(adapter.capabilities),
        )
    }

    override fun close() {
        positions.close()
        adapter.close()
        journal.close()
        shelf.close()
    }

    internal val listener =
        object : AdapterListener {
            /** Orders the gateway never placed (another tool's, on the same account) are not the client's. */
            override fun order(order: VenueOrder) {
                if (journal.order(order.clientOrderId) == null) return
                journal.appendOrder(WireMapping.order(order))
            }

            /** A fill is always of an order the client sent through this gateway (wire spec §4). */
            override fun fill(fill: VenueFill) {
                if (journal.order(fill.clientOrderId) == null) return
                if (journal.appendFill(WireMapping.fill(fill))) positions.changed(fill.symbol)
            }

            override fun settlement(settlement: VenueSettlement) {
                if (journal.appendSettlement(WireMapping.settlement(settlement))) positions.changed(settlement.symbol)
            }

            /** Funding is the account's, whoever's position it was charged on: every record is journaled. */
            override fun funding(funding: VenueFunding) {
                journal.appendFunding(WireFundingMapping.funding(funding))
            }

            override fun quote(quote: VenueQuote) = onQuote(quote)

            override fun quoteFeed(
                up: Boolean,
                reason: String,
            ) {
                log.info("venue quote feed {}: {}", if (up) "up" else "down", reason)
                quotesUp.set(up)
            }

            override fun connection(
                up: Boolean,
                reason: String,
            ) {
                log.info("venue link {}: {}", if (up) "up" else "down", reason)
                this@Gateway.up.set(up)
                onConnection(up)
            }
        }

    private fun sameBytes(
        a: String,
        b: String,
    ) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.Gateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Every client's quote subscription on one account (wire spec §4a): their codes, and every listed option of their
 * roots (by the listing's `underlying`), merged into one adapter subscription of codes; each venue quote delivered
 * to the clients that want it. The listing is read again at most every [listingRecheckMs] when an unknown code
 * arrives, and every [rootRefreshMs] while a root is wanted, so options the venue lists later (new expiries) are
 * subscribed too. Every quote a client holds is sent again with its time advanced every [refreshMs] while the venue
 * link is up, so a quiet book stays fresh and a dead link goes stale.
 */
class QuoteHub(
    private val gateway: Gateway,
    private val refreshMs: Long = 5_000,
    private val listingRecheckMs: Long = 60_000,
    private val rootRefreshMs: Long = 600_000,
) : AutoCloseable {
    /** One client's subscription; [send] must not block. */
    class Subscriber internal constructor(
        val codes: Set<String>,
        val roots: Set<String>,
        internal val send: (VenueQuote) -> Unit,
    )

    private val log = LoggerFactory.getLogger(QuoteHub::class.java)
    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val latest = ConcurrentHashMap<String, VenueQuote>()

    @Volatile private var underlyingOf: Map<String, String> = emptyMap()

    @Volatile private var listedAt: Long? = null

    @Volatile private var subscribed: Set<String>? = null
    private val timer =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "gateway-quotes").apply {
                isDaemon =
                    true
            }
        }

    /** Starts hearing the venue's quotes and refreshing held ones. */
    fun start() {
        gateway.onQuote = ::onQuote
        timer.scheduleWithFixedDelay(::refresh, refreshMs, refreshMs, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay(::expandRoots, rootRefreshMs, rootRefreshMs, TimeUnit.MILLISECONDS)
    }

    /** Subscribes [codes] and [roots] for [send]; closing the result ends the subscription. */
    fun subscribe(
        codes: Set<String>,
        roots: Set<String>,
        send: (VenueQuote) -> Unit,
    ): AutoCloseable {
        val subscriber = Subscriber(codes, roots, send)
        if (roots.isNotEmpty()) relist()
        subscribers += subscriber
        resubscribe()
        latest.values.filter { wants(subscriber, it.symbol) }.forEach(send)
        return AutoCloseable {
            subscribers -= subscriber
            resubscribe()
        }
    }

    override fun close() {
        timer.shutdownNow()
    }

    private fun onQuote(quote: VenueQuote) {
        latest[quote.symbol] = quote
        if (quote.symbol !in underlyingOf && subscribers.any { it.roots.isNotEmpty() }) relist()
        subscribers.filter { wants(it, quote.symbol) }.forEach { it.send(quote) }
    }

    private fun refresh() {
        // A quote is only refreshed while it can still change: a stopped feed must read as stale.
        if (!gateway.quotesLive) return
        val now = gateway.clock()
        for (subscriber in subscribers) {
            latest.values.filter { wants(subscriber, it.symbol) }.forEach { subscriber.send(it.copy(timeMs = now)) }
        }
    }

    private fun wants(
        subscriber: Subscriber,
        code: String,
    ) = code in subscriber.codes || underlyingOf[code]?.let { it in subscriber.roots } == true

    @Synchronized
    private fun resubscribe() {
        val roots = subscribers.flatMap { it.roots }.toSet()
        val options = underlyingOf.filterValues { it in roots }.keys
        val wanted = subscribers.flatMap { it.codes }.toSet() + options
        if (wanted == subscribed) return
        subscribed = wanted
        gateway.adapter.subscribeQuotes(wanted)
    }

    /** Reads the listing again and subscribes the options it now lists of every wanted root; a failure waits for the next. */
    private fun expandRoots() {
        if (subscribers.none { it.roots.isNotEmpty() }) return
        runCatching {
            relist(force = true)
            resubscribe()
        }.onFailure { log.warn("quote roots not expanded: {}", it.message) }
    }

    @Synchronized
    private fun relist(force: Boolean = false) {
        val now = gateway.clock()
        val last = listedAt
        if (!force && last != null && now - last < listingRecheckMs) return
        listedAt = now
        underlyingOf =
            gateway.adapter
                .instruments()
                .mapNotNull { i -> i.underlying?.let { i.code to it } }
                .toMap()
    }
}

package com.qkt.venuegateway.host.market

import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.host.Gateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Every client's quote subscription on one account (wire spec §4a): their codes and roots merged into
 * one adapter subscription, each venue quote delivered to the clients that want it (a root's options by
 * the listing's `underlying`, refreshed at most every [listingRecheckMs] when an unknown code arrives),
 * and every quote a client holds sent again with its time advanced every [refreshMs] while the venue
 * link is up, so a quiet book stays fresh and a dead link goes stale.
 */
class QuoteHub(
    private val gateway: Gateway,
    private val refreshMs: Long = 5_000,
    private val listingRecheckMs: Long = 60_000,
) : AutoCloseable {
    /** One client's subscription; [send] must not block. */
    class Subscriber internal constructor(
        val codes: Set<String>,
        val roots: Set<String>,
        internal val send: (VenueQuote) -> Unit,
    )

    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val latest = ConcurrentHashMap<String, VenueQuote>()

    @Volatile private var underlyingOf: Map<String, String> = emptyMap()

    @Volatile private var listedAt: Long? = null

    @Volatile private var subscribed: Pair<Set<String>, Set<String>> = emptySet<String>() to emptySet()
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
        val wanted = subscribers.flatMap { it.codes }.toSet() to subscribers.flatMap { it.roots }.toSet()
        if (wanted == subscribed) return
        subscribed = wanted
        gateway.adapter.subscribeQuotes(wanted.first, wanted.second)
    }

    @Synchronized
    private fun relist() {
        val now = gateway.clock()
        val last = listedAt
        if (last != null && now - last < listingRecheckMs) return
        listedAt = now
        underlyingOf =
            gateway.adapter
                .instruments()
                .mapNotNull { i -> i.underlying?.let { i.code to it } }
                .toMap()
    }
}

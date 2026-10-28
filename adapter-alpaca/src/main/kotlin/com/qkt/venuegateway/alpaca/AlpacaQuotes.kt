package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaQuoteStream
import com.qkt.venuegateway.support.SubscriptionSet

/** A quote socket: started once, sending while its link is up. */
interface AlpacaQuoteSocket : AutoCloseable {
    fun start()

    fun send(frame: String): Boolean
}

/**
 * Quotes of the subscribed codes from Alpaca's data streams: shares on the account's stock feed and, when the account
 * trades outside regular hours ([overnight]), also on Alpaca's overnight feed, which quotes 20:00-04:00 New York
 * while the stock feeds are silent; coin pairs on the crypto feed. Each socket opens at its first subscription
 * ([open]) and subscribes everything it carries again whenever it reconnects. Alpaca names a pair `BTC/USD` on the
 * wire; the mapping pushes it under its code.
 */
class AlpacaQuotes(
    private val listing: AlpacaListing,
    private val overnight: Boolean,
    private val open: (feed: String, symbols: SubscriptionSet<String>) -> Pair<AlpacaQuoteStream, AlpacaQuoteSocket>,
) : AutoCloseable {
    private class Feed(
        val name: String,
    ) {
        val symbols = SubscriptionSet<String>()
        var link: Pair<AlpacaQuoteStream, AlpacaQuoteSocket>? = null
    }

    private val coins = listOf(Feed(CRYPTO))
    private val shares = listOfNotNull(Feed(STOCKS), Feed(OVERNIGHT).takeIf { overnight })

    /**
     * Quotes exactly [codes] from now on, replacing the last subscription: what is new is subscribed, what is no longer
     * asked for unsubscribed. An unlisted code is skipped (the host may ask for codes Alpaca does not list).
     */
    @Synchronized
    fun subscribe(codes: Set<String>) {
        val wanted = codes.mapNotNull(listing::find).groupBy({ it.crypto }, { it.symbol })
        for ((crypto, feeds) in listOf(true to coins, false to shares)) {
            val symbols = wanted[crypto].orEmpty()
            for (feed in feeds) {
                val removed = feed.symbols.drop(feed.symbols.all() - symbols.toSet())
                val added = feed.symbols.want(symbols)
                val link = feed.link
                when {
                    link != null -> {
                        link.first.unsubscription(removed)?.let(link.second::send)
                        link.first.subscription(added)?.let(link.second::send)
                    }
                    added.isNotEmpty() -> feed.link = open(feed.name, feed.symbols).also { it.second.start() }
                }
            }
        }
    }

    override fun close() = (coins + shares).forEach { it.link?.second?.close() }

    companion object {
        const val CRYPTO = "crypto"
        const val STOCKS = "stocks"
        const val OVERNIGHT = "overnight"
    }
}

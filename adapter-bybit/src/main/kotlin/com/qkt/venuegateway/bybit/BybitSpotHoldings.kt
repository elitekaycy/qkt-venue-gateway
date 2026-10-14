package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.PositionRow
import com.qkt.venuegateway.bybit.BybitErrors.venue
import com.qkt.venuegateway.bybit.client.BybitPrivateClient
import com.qkt.venuegateway.bybit.client.BybitPublicClient

/**
 * A spot account's positions: the coins it holds, each as the listed pair that trades it against the account's
 * quote coin (a BTC balance is a `BTCUSDT` position of that many BTC), netted. Bybit keeps no entry price of a
 * coin (its wallet has none, and a deposit has no price), so a holding's average price is the pair's last
 * traded price when it is read: what it is worth, not what it cost. Coins with no listed pair, and the quote
 * coin itself, are not positions. A buy's fee is taken in the coin bought, so a holding grows by less than the
 * fill.
 */
internal class BybitSpotHoldings(
    private val account: BybitPrivateClient,
    private val market: BybitPublicClient,
    private val listing: BybitListing,
) {
    fun rows(): List<PositionRow> {
        val coins = venue { account.coins() }
        val pairs = venue { listing.all() }.associateBy { it.baseCoin }
        return coins.filterValues { it.signum() != 0 }.mapNotNull { (coin, held) ->
            val pair = pairs[coin] ?: return@mapNotNull null
            val last =
                venue { market.ticker(pair.category, pair.symbol) }?.last
                    ?: error("bybit has no last price of ${pair.symbol}")
            PositionRow(pair.symbol, held, last)
        }
    }
}

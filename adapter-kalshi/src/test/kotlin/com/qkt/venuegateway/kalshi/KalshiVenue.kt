package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueFunding
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueSettlement
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Kalshi demo account replayed from its recordings: the KXNBA3D markets listed, the account holding +2 of
 * [CURRY] and −1 of the gas market, and the orders, fills and answers recorded while trading them.
 */
class KalshiVenue : AutoCloseable {
    val server =
        Fixtures.Server { method, path, query, body ->
            when ("$method $path") {
                "GET /series" -> "demo/series-list.json"
                "GET /series/KXNBA3D" -> "demo/series-KXNBA3D.json"
                "GET /events" -> "demo/events-KXNBA3D.json"
                "GET /markets" -> "demo/markets-by-tickers.json"
                "GET /markets/$CURRY" -> "demo/market-$CURRY.json"
                "GET /markets/$CURRY/orderbook" -> "demo/orderbook-$CURRY.json"
                "GET /markets/trades" -> "demo/trades-$CURRY.json"
                "GET /series/KXNBA3D/markets/$CURRY/candlesticks" -> "demo/candles-1m-$CURRY.json"
                "GET /portfolio/balance" -> "private/balance.json"
                "GET /portfolio/positions" -> "private/positions.json"
                "GET /portfolio/orders" ->
                    if (query["status"] == "resting") "private/orders-resting.json" else "private/orders-all.json"
                "GET /portfolio/orders/$RESTING" -> "private/order-amended.json"
                "GET /portfolio/orders/$FILLED" -> "private/order-filled.json"
                "GET /portfolio/orders/$ASKED" -> "private/order-ask-filled.json"
                "GET /portfolio/fills" -> "private/fills.json"
                "GET /portfolio/settlements" -> "private/settlements.json"
                "POST /portfolio/events/orders" -> placement(body)
                "POST /portfolio/events/orders/$RESTING/amend" -> "private/amend.json"
                "DELETE /portfolio/events/orders/$RESTING" -> "private/cancel.json"
                else -> null
            }
        }
    val orders = CopyOnWriteArrayList<VenueOrder>()
    val fills = CopyOnWriteArrayList<VenueFill>()
    val quotes = CopyOnWriteArrayList<VenueQuote>()
    val links = CopyOnWriteArrayList<Boolean>()
    val listener =
        object : AdapterListener {
            override fun order(order: VenueOrder) {
                orders += order
            }

            override fun fill(fill: VenueFill) {
                fills += fill
            }

            override fun settlement(settlement: VenueSettlement) = Unit

            override fun funding(funding: VenueFunding) = Unit

            override fun quote(quote: VenueQuote) {
                quotes += quote
            }

            override fun quoteFeed(
                up: Boolean,
                reason: String,
            ) = Unit

            override fun connection(
                up: Boolean,
                reason: String,
            ) {
                links += up
            }
        }

    /** The adapter on shard [shard], its every call answered by [server]. */
    fun adapter(
        shard: Int = 0,
        clock: () -> Long = System::currentTimeMillis,
    ): VenueAdapter {
        val context =
            AdapterContext(
                mapOf("environment" to "demo", "shard" to "$shard"),
                clock,
                Files.createTempDirectory("kalshi-venue"),
                Credentials("key-id", Fixtures.pem),
            )
        return KalshiAdapterFactory { server.restUrl to server.socketUrl }.create(context)
    }

    fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    override fun close() = server.close()

    /** The answer Kalshi recorded for the placement whose label [body] carries. */
    private fun placement(body: String): String? =
        when {
            "\"ghost\"" in body || "\"qkt-probe-fill\"" in body -> "!409 private/place-duplicate.json"
            "\"funds\"" in body -> "!400 private/place-funds.json"
            "\"lost\"" in body -> "!201 private/place-wti.json"
            else -> "!201 private/place-resting.json"
        }

    companion object {
        const val CURRY = "KXNBA3D-26NOV05MIAGSW-GSWSCURRY30"
        const val RESTING = "01a1ae61-bf60-7752-859e-2842fa23b0ba"
        const val FILLED = "01a1ae61-f228-76f6-9226-9c3a49437fb5"
        const val ASKED = "01a1ae63-1338-7a82-b375-3de37729da04"
    }
}

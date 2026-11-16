package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.alpaca.client.AlpacaData
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import kotlinx.serialization.json.JsonObject

/**
 * A coin-pair adapter over recorded [routes], keeping the trade stream's two callbacks ([update], [link]) and the
 * quote feed's two ([quote], [feed]) so a test can play what Alpaca's sockets would push.
 */
internal class WiredAdapter(
    routes: Map<String, List<String>>,
) {
    val http = FixtureHttp(routes)
    lateinit var update: (JsonObject) -> Unit
    lateinit var link: (Boolean, String) -> Unit
    lateinit var quote: (VenueQuote) -> Unit
    lateinit var feed: (Boolean, String) -> Unit
    var started = 0
    var closed = 0

    val adapter =
        AlpacaAdapter(
            AlpacaSettings(AlpacaEnvironment.PAPER, assetClasses = setOf("crypto")),
            AlpacaTrading(http),
            AlpacaData(http, "iex"),
            { 0L },
            { onUpdate, onConnection ->
                update = onUpdate
                link = onConnection
                object : AlpacaLink {
                    override fun start() {
                        started++
                    }

                    override fun close() {
                        closed++
                    }
                }
            },
            { listing, onQuote, onFeed ->
                quote = onQuote
                feed = onFeed
                AlpacaQuotes(listing, false) { _, _ -> error("no quote sockets in this test") }
            },
        )
}

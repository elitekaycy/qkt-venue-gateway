package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import com.qkt.venuegateway.alpaca.client.AlpacaData
import com.qkt.venuegateway.alpaca.client.AlpacaJsonHttp
import com.qkt.venuegateway.alpaca.client.AlpacaQuoteStream
import com.qkt.venuegateway.alpaca.client.AlpacaTradeStream
import com.qkt.venuegateway.alpaca.client.AlpacaTrading
import com.qkt.venuegateway.support.ReconnectingSocket
import java.util.concurrent.TimeUnit

/** `GATEWAY_ADAPTER=alpaca`: one Alpaca account, on the environment its settings name ([AlpacaSettings]). */
class AlpacaAdapterFactory : VenueAdapterFactory {
    override val type = "alpaca"

    override fun create(context: AdapterContext): VenueAdapter {
        val settings = AlpacaSettings.of(context)
        val (key, secret) = context.requiredCredentials()
        val environment = settings.environment
        // Every call goes through the host's client; Alpaca's streams answer WebSocket pings, one every 20 s.
        val http = context.http
        val sockets = http.newBuilder().pingInterval(PING_S, TimeUnit.SECONDS).build()
        val urls =
            mapOf(
                AlpacaQuotes.CRYPTO to "${settings.dataStreamUrl}/v1beta3/crypto/us",
                AlpacaQuotes.STOCKS to "${settings.dataStreamUrl}/v2/${settings.feed}",
                AlpacaQuotes.OVERNIGHT to "${settings.dataStreamUrl}/v1beta1/overnight",
            )
        return AlpacaAdapter(
            settings,
            AlpacaTrading(
                AlpacaJsonHttp(environment.tradingUrl, key, secret, http),
                AlpacaJsonHttp(environment.tradingUrl, key, secret, http, timeoutMs = LIST_TIMEOUT_MS),
            ),
            AlpacaData(AlpacaJsonHttp(settings.dataUrl, key, secret, http), settings.feed),
            context.clock,
            { onUpdate, onConnection ->
                val socket =
                    ReconnectingSocket(
                        environment.streamUrl,
                        "alpaca-trades",
                        AlpacaTradeStream(key, secret, onUpdate, onConnection),
                        http = sockets,
                    )
                object : AlpacaLink {
                    override fun start() = socket.start()

                    override fun close() = socket.close()
                }
            },
            { listing, onQuote, onFeed ->
                AlpacaQuotes(listing, settings.extendedHours) { feed, symbols ->
                    val stream = AlpacaQuoteStream(key, secret, symbols, { onQuote(AlpacaMapping.quote(it)) }, onFeed)
                    val socket = ReconnectingSocket(urls.getValue(feed), "alpaca-$feed", stream, http = sockets)
                    stream to
                        object : AlpacaQuoteSocket {
                            override fun start() = socket.start()

                            override fun send(frame: String) = socket.send(frame)

                            override fun close() = socket.close()
                        }
                }
            },
        )
    }

    private companion object {
        const val PING_S = 20L

        /** The whole share list is 6.5 MB: measured on paper from 2 s to 166 s (2026-10-28). */
        const val LIST_TIMEOUT_MS = 300_000L
    }
}

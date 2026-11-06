package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.adapter.VenueAdapterFactory
import com.qkt.venuegateway.kalshi.client.KalshiAuth
import com.qkt.venuegateway.kalshi.client.KalshiPortfolio
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiRest
import com.qkt.venuegateway.kalshi.client.KalshiSocket

/**
 * `GATEWAY_ADAPTER=kalshi`: one shard of one Kalshi account on the environment its settings name ([KalshiSettings]).
 * The login is the API key id and the secret its PEM private key (Ed25519 or RSA). The public market is read
 * unsigned; the listing's pages are slow (measured up to minutes on production), so its calls wait up to a minute.
 * [urls] answers each environment's REST base and socket URL (the environment's own, unless a test points it at a
 * local server).
 */
class KalshiAdapterFactory(
    private val urls: (KalshiEnvironment) -> Pair<String, String> = { it.restUrl to it.socketUrl },
) : VenueAdapterFactory {
    override val type = "kalshi"

    override fun create(context: AdapterContext): VenueAdapter {
        val settings = KalshiSettings.of(context)
        val credentials = context.requiredCredentials()
        val auth = KalshiAuth(credentials.login, credentials.secret)
        val (url, socketUrl) = urls(settings.environment)
        val public = KalshiPublic(KalshiRest(url, timeoutMs = LISTING_TIMEOUT_MS, client = context.http))
        val portfolio = KalshiPortfolio(KalshiRest(url, auth, context.clock, client = context.http))
        return KalshiAdapter(context, settings, public, portfolio, socket(socketUrl, auth, context))
    }

    private fun socket(
        url: String,
        auth: KalshiAuth,
        context: AdapterContext,
    ) = KalshiLink { onFill, onOrder, onTicker, onConnection ->
        val socket =
            KalshiSocket(
                url,
                auth,
                context.clock,
                onFill,
                onOrder,
                onTicker,
                onConnection,
                context.http,
            )
        object : KalshiStream {
            override fun start() = socket.start()

            override fun quote(markets: List<String>) = socket.quote(markets)

            override fun close() = socket.close()
        }
    }

    private companion object {
        const val LISTING_TIMEOUT_MS = 60_000L
    }
}

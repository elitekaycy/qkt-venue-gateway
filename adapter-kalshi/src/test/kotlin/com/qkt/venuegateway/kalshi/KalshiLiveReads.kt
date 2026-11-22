package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.kalshi.client.KalshiEvent
import com.qkt.venuegateway.kalshi.client.KalshiException
import com.qkt.venuegateway.kalshi.client.KalshiMarket
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import com.qkt.venuegateway.kalshi.client.KalshiRest
import com.qkt.venuegateway.support.JsonReads.objects

/** Reads of Kalshi's public market data for live tests: paced under its request limit, archived markets included. */
internal object KalshiLiveReads {
    private const val PAUSE_MS = 300L
    private const val RETRIES = 4
    private const val BACKOFF = 4
    private const val TOO_MANY_REQUESTS = 429

    /** [call] paced to stay under Kalshi's request limit; a 429 backs off and tries again. */
    fun <T> paced(call: () -> T): T {
        var wait = PAUSE_MS
        repeat(RETRIES) {
            Thread.sleep(wait)
            try {
                return call()
            } catch (e: KalshiException) {
                if (e.status != TOO_MANY_REQUESTS) throw e
                wait *= BACKOFF
            }
        }
        return call()
    }

    /** [event] with its markets; one settled before Kalshi's archive cutoff keeps them at `/historical/markets`. */
    fun archived(
        rest: KalshiRest,
        event: KalshiEvent,
    ): KalshiEvent {
        if (event.markets.isNotEmpty()) return event
        val page = paced { rest.get("/historical/markets", listOf("event_ticker" to event.eventTicker)) }
        return event.copy(markets = page.objects("markets").map(KalshiMarketJson::market))
    }

    /** Up to [limit] open combo markets (multivariate events): Kalshi lists them only under `mve_filter=only`. */
    fun combos(
        rest: KalshiRest,
        limit: Int,
    ): List<KalshiMarket> =
        paced { rest.get("/markets", listOf("mve_filter" to "only", "limit" to "$limit")) }
            .objects("markets")
            .map(KalshiMarketJson::market)
}

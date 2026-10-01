package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireBars
import com.qkt.vgp.WireQuote
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Market data (wire spec §3 bars, §4a quotes): `GET /v1/bars`, closed bars only, a page of at most
 * [BARS_PAGE] with `next`; and `GET /v1/quotes` (WebSocket), the [hub]'s quotes for the client's codes
 * and roots, never replayed. A slow client loses its oldest buffered quotes, never the newest.
 */
internal fun Route.marketRoutes(
    gateway: Gateway,
    hub: QuoteHub,
) {
    get("/v1/bars") {
        call.serve(gateway, ANY_ROLE) {
            val q = request.queryParameters
            val code = q["symbol"] ?: throw InvalidRequestException("symbol missing")
            val window = q["window_ms"]?.toLongOrNull() ?: throw InvalidRequestException("window_ms missing")
            if (window <= 0 || window % MINUTE_MS != 0L || DAY_MS % window != 0L) {
                throw InvalidRequestException("window_ms must be whole minutes dividing a day: $window")
            }
            val from = q["from"]?.toLongOrNull() ?: throw InvalidRequestException("from missing")
            val to = q["to"]?.toLongOrNull() ?: throw InvalidRequestException("to missing")
            val closedBy = gateway.clock()
            val bars =
                gateway.adapter
                    .bars(code, window, from, to)
                    .filter { it.startMs in from until to && it.startMs + window <= closedBy }
                    .sortedBy { it.startMs }
            val page = bars.take(BARS_PAGE)
            json(WireBars.serializer(), WireBars(page.map(WireReads::bar), bars.getOrNull(BARS_PAGE)?.startMs))
        }
    }
    webSocket("/v1/quotes") {
        if (gateway.roleOf(call.request.headers["Authorization"]) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val q = call.request.queryParameters
        val codes =
            q["symbols"]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        val roots =
            q["roots"]
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        val buffer = Channel<WireQuote>(QUOTE_BUFFER, BufferOverflow.DROP_OLDEST)
        hub.subscribe(codes, roots) { buffer.trySend(WireReads.quote(it)) }.use {
            for (quote in buffer) send(Frame.Text(wireJson.encodeToString(WireQuote.serializer(), quote)))
        }
    }
}

private const val BARS_PAGE = 1000
private const val QUOTE_BUFFER = 10_000
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L

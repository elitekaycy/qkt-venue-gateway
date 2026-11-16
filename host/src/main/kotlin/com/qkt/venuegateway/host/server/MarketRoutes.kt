package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.market.BarBuilder
import com.qkt.venuegateway.host.market.QuoteHub
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireBars
import com.qkt.vgp.WireQuote
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.builtins.ListSerializer

/**
 * Market data (wire spec §3 bars, §4a quotes): `GET /v1/bars`, closed bars only ([BarBuilder]), a page of at most
 * [BARS_PAGE] windows, each page's klines asked of the adapter alone (so no venue call is larger than a page),
 * with `next` the start of the following page while closed time remains before `to`; and `GET /v1/quotes`
 * (WebSocket), the [hub]'s quotes for the client's codes and roots, never replayed (a missing or wrong token is `401`
 * before the upgrade): one quote a message, or with `refresh=changes` one JSON array of changed quotes a message (any
 * other `refresh` closes the socket naming it). A slow client loses its oldest buffered messages, never the newest.
 */
internal fun Route.marketRoutes(
    gateway: Gateway,
    hub: QuoteHub,
) {
    get("/v1/bars") {
        call.serve(gateway, ANY_ROLE) {
            val q = request.queryParameters
            val code = q["symbol"] ?: throw InvalidRequestException("symbol missing")
            val window = windowMs(q["window_ms"])
            val from = q["from"]?.toLongOrNull() ?: throw InvalidRequestException("from missing")
            val to = q["to"]?.toLongOrNull() ?: throw InvalidRequestException("to missing")
            val closedBy = gateway.clock()
            val last = minOf(to, closedBy)
            val end = minOf(last, from + BARS_PAGE * window)
            val adapter = gateway.adapter
            val page = if (from < end) BarBuilder.closed(adapter, code, window, from, end, closedBy) else emptyList()
            json(WireBars.serializer(), WireBars(page.map(WireReads::bar), end.takeIf { it < last }))
        }
    }
    tokenWebSocket(gateway, "/v1/quotes") {
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
        when (q["refresh"]) {
            null -> {
                val buffer = Channel<WireQuote>(QUOTE_BUFFER, BufferOverflow.DROP_OLDEST)
                val subscription = hub.subscribe(codes, roots) { buffer.trySend(WireReads.quote(it)) }
                try {
                    for (quote in buffer) send(Frame.Text(wireJson.encodeToString(WireQuote.serializer(), quote)))
                } finally {
                    subscription.close()
                }
            }
            "changes" -> {
                val batches = Channel<List<WireQuote>>(BATCH_BUFFER, BufferOverflow.DROP_OLDEST)
                val subscription = hub.subscribeChanges(codes, roots) { batches.trySend(it.map(WireReads::quote)) }
                try {
                    for (batch in batches) send(Frame.Text(wireJson.encodeToString(QUOTE_BATCH, batch)))
                } finally {
                    subscription.close()
                }
            }
            else -> close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "refresh must be changes"))
        }
    }
}

/** [raw] as a window length: whole minutes dividing a day, or a bad request naming it. */
internal fun windowMs(raw: String?): Long {
    val window = raw?.toLongOrNull() ?: throw InvalidRequestException("window_ms missing")
    if (window <= 0 || window % MINUTE_MS != 0L || DAY_MS % window != 0L) {
        throw InvalidRequestException("window_ms must be whole minutes dividing a day: $window")
    }
    return window
}

private const val BARS_PAGE = 1000
private const val QUOTE_BUFFER = 10_000
private const val BATCH_BUFFER = 1_000
private val QUOTE_BATCH = ListSerializer(WireQuote.serializer())
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L

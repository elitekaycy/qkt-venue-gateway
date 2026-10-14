package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.vgp.WireOpenInterest
import com.qkt.vgp.WireOpenInterestPoint
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/open-interest` (wire spec §3): one contract's published open interest up to now, a page of at most
 * [OPEN_INTEREST_PAGE] figures over at most [OPEN_INTEREST_WINDOW_MS] (a thousand minutes), asked of the
 * adapter one page at a time, with `next` the `from` of the following page while the range goes on.
 * `501 unsupported` unless the adapter declares open interest.
 */
internal fun Route.openInterestRoutes(gateway: Gateway) {
    get("/v1/open-interest") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.OPEN_INTEREST, "open interest")
            val code = request.queryParameters["symbol"] ?: throw InvalidRequestException("symbol missing")
            val from = long("from")
            val to = long("to")
            val asked = minOf(to, from + OPEN_INTEREST_WINDOW_MS - 1)
            val read =
                if (from >
                    minOf(asked, gateway.clock())
                ) {
                    emptyList()
                } else {
                    gateway.adapter.openInterest(code, from, asked)
                }
            // Now is taken after the read, so a figure the read itself made known (an adapter recording the venue's
            // present figure) is served at once rather than on the next request.
            val last = minOf(to, gateway.clock())
            val end = minOf(asked, last)
            val figures = read.filter { it.timeMs in from..end }.sortedBy { it.timeMs }
            val page = figures.take(OPEN_INTEREST_PAGE)
            val next =
                when {
                    figures.size > OPEN_INTEREST_PAGE -> page.last().timeMs + 1
                    end < last -> end + 1
                    else -> null
                }
            val points = page.map { WireOpenInterestPoint(it.timeMs, it.openInterest.toPlainString()) }
            json(WireOpenInterest.serializer(), WireOpenInterest(points, next))
        }
    }
}

private const val OPEN_INTEREST_PAGE = 1000
private const val OPEN_INTEREST_WINDOW_MS = OPEN_INTEREST_PAGE * 60_000L

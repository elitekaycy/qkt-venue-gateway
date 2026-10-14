package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueLevel
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.vgp.WireDepth
import com.qkt.vgp.WireDepthSnapshot
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/depth` (wire spec §3): one contract's recorded order-book snapshots up to now, a page of at most
 * [DEPTH_PAGE] snapshots over at most [DEPTH_WINDOW_MS] (a thousand minutes), asked of the adapter one page at
 * a time, with `next` the `from` of the following page while the range goes on. Each side is cut to its best
 * [VenueDepth.MAX_LEVELS] levels, so the wire never carries more than it promises. `501 unsupported` unless
 * the adapter declares depth.
 */
internal fun Route.depthRoutes(gateway: Gateway) {
    get("/v1/depth") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.DEPTH, "depth")
            val code = request.queryParameters["symbol"] ?: throw InvalidRequestException("symbol missing")
            val from = long("from")
            val to = long("to")
            val asked = minOf(to, from + DEPTH_WINDOW_MS - 1)
            val read =
                if (from >
                    minOf(asked, gateway.clock())
                ) {
                    emptyList()
                } else {
                    gateway.adapter.depth(code, from, asked)
                }
            // Now is taken after the read, so a snapshot the read itself recorded is served at once.
            val last = minOf(to, gateway.clock())
            val end = minOf(asked, last)
            val snapshots = read.filter { it.timeMs in from..end }.sortedBy { it.timeMs }
            val page = snapshots.take(DEPTH_PAGE)
            val next =
                when {
                    snapshots.size > DEPTH_PAGE -> page.last().timeMs + 1
                    end < last -> end + 1
                    else -> null
                }
            json(WireDepth.serializer(), WireDepth(page.map(::wire), next))
        }
    }
}

private fun wire(d: VenueDepth) = WireDepthSnapshot(d.timeMs, levels(d.bids), levels(d.asks))

private fun levels(side: List<VenueLevel>) =
    side.take(VenueDepth.MAX_LEVELS).map { listOf(it.price.toPlainString(), it.amount.toPlainString()) }

private const val DEPTH_PAGE = 1000
private const val DEPTH_WINDOW_MS = DEPTH_PAGE * 60_000L

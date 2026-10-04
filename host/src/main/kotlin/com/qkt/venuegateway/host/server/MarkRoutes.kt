package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireMarks
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Mark prices (wire spec §3): `GET /v1/marks`, a contract's mark and index sampled once per window of
 * `window_ms` (whole minutes dividing a day): for each window starting in `[from, to)` that has closed and in
 * which the venue reported them, the last report, at its own time. A page covers at most [MARKS_PAGE]
 * windows, asked of the adapter alone, with `next` the start of the following page while closed time
 * remains before `to`. A report outside the windows asked for, or an earlier one in the same window, is
 * dropped, so each window holds one sample whatever the adapter answers. `501 unsupported` unless the
 * adapter declares mark prices.
 */
internal fun Route.markRoutes(gateway: Gateway) {
    get("/v1/marks") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.MARK_PRICES, "mark prices")
            val code = request.queryParameters["symbol"] ?: throw InvalidRequestException("symbol missing")
            val window = windowMs(request.queryParameters["window_ms"])
            val from = long("from")
            val closedBy = gateway.clock()
            val last = minOf(long("to"), closedBy)
            val end = minOf(last, from + MARKS_PAGE * window)
            val marks =
                if (from >= end) {
                    emptyList()
                } else {
                    gateway.adapter
                        .marks(code, window, from, end)
                        .filter { it.timeMs in from until end && it.timeMs / window * window + window <= closedBy }
                        .groupBy { it.timeMs / window }
                        .map { (_, inWindow) -> inWindow.maxBy { it.timeMs } }
                        .sortedBy { it.timeMs }
                }
            json(WireMarks.serializer(), WireMarks(marks.map(WireReads::mark), end.takeIf { it < last }))
        }
    }
}

/** Windows per page: each may cost the adapter a venue call, so a page stays well inside an HTTP timeout. */
private const val MARKS_PAGE = 100L

package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireLiquidations
import com.qkt.vgp.WireTrades
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The public tape (wire spec §3), prints whose time is in `[from, min(to, now))`, oldest first:
 * - `GET /v1/trades`: every print with its aggressor side, at most [TRADES_PAGE] a page, asked of the adapter as
 *   one page. A full page ends before its last millisecond, whose prints all open the next page (`next` is that
 *   millisecond), so no print is served twice or split from its instant; a millisecond holding a whole page
 *   cannot be paged and fails the request.
 * - `GET /v1/liquidations`: the prints that liquidated a position, over at most [LIQUIDATIONS_SPAN_MS] a page
 *   (the adapter may have to read the whole tape to find them), `next` the following span's start.
 *
 * Each answers `501 unsupported` unless the adapter declares it.
 */
internal fun Route.tapeRoutes(gateway: Gateway) {
    get("/v1/trades") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.TRADES, "trades")
            val code = symbol()
            val from = long("from")
            val last = minOf(long("to"), gateway.clock())
            val prints =
                if (from >= last) emptyList() else gateway.adapter.trades(code, from, last, TRADES_PAGE)
            val inRange = prints.filter { it.timeMs in from until last }.sortedBy { it.timeMs }
            if (prints.size < TRADES_PAGE) {
                return@serve json(WireTrades.serializer(), WireTrades(inRange.map(WireReads::print)))
            }
            val cut = prints.maxOf { it.timeMs }
            check(cut > from) { "the trades of $code at $from ms fill a page of $TRADES_PAGE; they cannot be paged" }
            json(
                WireTrades.serializer(),
                WireTrades(inRange.filter { it.timeMs < cut }.map(WireReads::print), cut),
            )
        }
    }
    get("/v1/liquidations") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.LIQUIDATIONS, "liquidations")
            val code = symbol()
            val from = long("from")
            val last = minOf(long("to"), gateway.clock())
            val end = minOf(last, from + LIQUIDATIONS_SPAN_MS)
            val prints =
                if (from >= end) {
                    emptyList()
                } else {
                    gateway.adapter
                        .liquidations(code, from, end)
                        .filter { it.timeMs in from until end }
                        .sortedBy { it.timeMs }
                }
            json(
                WireLiquidations.serializer(),
                WireLiquidations(prints.map(WireReads::print), end.takeIf { it < last }),
            )
        }
    }
}

private fun ApplicationCall.symbol(): String =
    request.queryParameters["symbol"] ?: throw InvalidRequestException("symbol missing")

/** Prints per page of the tape: one adapter call, as many as a venue answers at once (Deribit: 1000). */
internal const val TRADES_PAGE = 1000

/** Time per page of liquidations: finding them may cost the adapter a read of the whole tape over it. */
internal const val LIQUIDATIONS_SPAN_MS = 3_600_000L

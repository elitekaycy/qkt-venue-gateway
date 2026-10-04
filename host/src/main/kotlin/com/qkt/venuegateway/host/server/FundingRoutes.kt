package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.journal.funding
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.venuegateway.host.wire.WireFundingMapping
import com.qkt.vgp.WireFundingRates
import com.qkt.vgp.WireFundings
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Funding (wire spec §3): `GET /v1/funding`, what the venue charged or credited the account, from the
 * journal; and `GET /v1/funding-rates`, one perpetual's published rates up to now, a page of at most
 * [RATES_PAGE] rates over at most [RATES_WINDOW_MS] (a thousand hourly rates), asked of the adapter one page
 * at a time, with `next` the `from` of the following page while the range goes on. Each answers
 * `501 unsupported` unless the adapter declares it.
 */
internal fun Route.fundingRoutes(gateway: Gateway) {
    get("/v1/funding") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.FUNDING, "funding")
            json(WireFundings.serializer(), WireFundings(gateway.journal.funding(long("from"), long("to"))))
        }
    }
    get("/v1/funding-rates") {
        call.serve(gateway, ANY_ROLE) {
            gateway.require(Capability.FUNDING_RATES, "funding rates")
            val code = request.queryParameters["symbol"] ?: throw InvalidRequestException("symbol missing")
            val from = long("from")
            val last = minOf(long("to"), gateway.clock())
            val end = minOf(last, from + RATES_WINDOW_MS - 1)
            val rates =
                if (from > end) {
                    emptyList()
                } else {
                    gateway.adapter
                        .fundingRates(code, from, end)
                        .filter { it.timeMs in from..end }
                        .sortedBy { it.timeMs }
                }
            val page = rates.take(RATES_PAGE)
            val next =
                when {
                    rates.size > RATES_PAGE -> page.last().timeMs + 1
                    end < last -> end + 1
                    else -> null
                }
            json(WireFundingRates.serializer(), WireFundingRates(page.map(WireFundingMapping::rate), next))
        }
    }
}

private fun Gateway.require(
    capability: Capability,
    what: String,
) {
    if (capability !in adapter.capabilities) throw VenueUnsupportedException(what)
}

private fun ApplicationCall.long(name: String): Long =
    request.queryParameters[name]?.toLongOrNull() ?: throw InvalidRequestException("$name must be epoch milliseconds")

private const val RATES_PAGE = 1000
private const val RATES_WINDOW_MS = RATES_PAGE * 3_600_000L

package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.market.CatalogQuery
import com.qkt.vgp.WireInstrument
import com.qkt.vgp.WireInstrumentPage
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.builtins.ListSerializer

/**
 * `GET /v1/instruments` and `GET /v1/instruments/{code}` (wire spec §3), served from the [Gateway.catalog]'s last
 * read: the whole listing as a list when no catalog parameter is given (as every gateway served it), else the page
 * the [CatalogQuery] asks for (a series or an event from the venue itself when the adapter can search it, so a
 * catalogue too large to list is still found); one code from the index, else as the adapter answers for it, else
 * `404`.
 */
internal fun Route.instrumentRoutes(gateway: Gateway) {
    val catalog = gateway.catalog
    get("/v1/instruments") {
        call.serve(gateway, ANY_ROLE) {
            val query = CatalogQuery.of { request.queryParameters[it] }
            if (query == null) {
                json(ListSerializer(WireInstrument.serializer()), catalog.current().listing)
            } else {
                val read = query.search()?.let(catalog::search) ?: catalog.current()
                json(WireInstrumentPage.serializer(), query.page(read, catalog.changes, gateway.clock()))
            }
        }
    }
    get("/v1/instruments/{code}") {
        call.serve(gateway, ANY_ROLE) {
            val code = parameters["code"]!!
            catalog.find(code)?.let { json(WireInstrument.serializer(), it) }
                ?: error(404, "not_found", "no instrument $code")
        }
    }
}

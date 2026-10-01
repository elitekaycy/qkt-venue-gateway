package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.venuegateway.host.wire.InvalidRequestException
import com.qkt.venuegateway.host.wire.WireMapping
import com.qkt.venuegateway.host.wire.WireReads
import com.qkt.vgp.WireAccount
import com.qkt.vgp.WireDeals
import com.qkt.vgp.WireHealth
import com.qkt.vgp.WireInstrument
import com.qkt.vgp.WireKillSwitch
import com.qkt.vgp.WireOrder
import com.qkt.vgp.WireOrders
import com.qkt.vgp.WirePositions
import com.qkt.vgp.WireSettlements
import com.qkt.vgp.WireSubmit
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The REST half of VGP v1 (wire spec §3), served from [gateway]. */
internal fun Route.restRoutes(gateway: Gateway) {
    val trader = setOf(Role.TRADER)
    val guardian = setOf(Role.GUARDIAN)
    get("/v1/health") { call.serve(gateway, ANY_ROLE) { json(WireHealth.serializer(), gateway.health()) } }
    get("/v1/account") {
        call.serve(gateway, ANY_ROLE) { json(WireAccount.serializer(), WireReads.account(gateway.adapter.account())) }
    }
    get("/v1/instruments") {
        call.serve(gateway, ANY_ROLE) {
            json(ListSerializer(WireInstrument.serializer()), gateway.adapter.instruments().map(WireReads::instrument))
        }
    }
    get("/v1/positions") {
        call.serve(
            gateway,
            ANY_ROLE,
        ) { json(WirePositions.serializer(), WireReads.positions(gateway.adapter.positions())) }
    }
    get("/v1/orders") {
        call.serve(gateway, ANY_ROLE) {
            json(WireOrders.serializer(), WireOrders(gateway.adapter.openOrders().map(WireMapping::order)))
        }
    }
    post("/v1/orders") {
        call.serve(gateway, trader) {
            val body = wireJson.decodeFromString(WireSubmit.serializer(), receiveText())
            desk(gateway.desk.submit(body)) {
                json(WireOrder.serializer(), it.order, if (it.created) HttpStatusCode.Created else HttpStatusCode.OK)
            }
        }
    }
    get("/v1/orders/{id}") {
        call.serve(gateway, ANY_ROLE) { desk(gateway.desk.get(id())) { json(WireOrder.serializer(), it.order) } }
    }
    delete("/v1/orders/{id}") {
        call.serve(gateway, trader) { desk(gateway.desk.cancel(id())) { json(WireOrder.serializer(), it.order) } }
    }
    get("/v1/deals") {
        call.serve(gateway, ANY_ROLE) {
            val id = request.queryParameters["client_order_id"]
            val deals =
                if (id !=
                    null
                ) {
                    gateway.journal.fillsOf(id)
                } else {
                    gateway.journal.fills(param("from"), param("to"))
                }
            json(WireDeals.serializer(), WireDeals(deals))
        }
    }
    get("/v1/settlements") {
        call.serve(gateway, ANY_ROLE) {
            val symbol = request.queryParameters["symbol"]
            val settled =
                if (symbol !=
                    null
                ) {
                    gateway.journal.settlementsOf(symbol)
                } else {
                    gateway.journal.settlements(param("from"), param("to"))
                }
            json(WireSettlements.serializer(), WireSettlements(settled))
        }
    }
    post("/v1/kill") { call.serve(gateway, guardian) { killSwitch(gateway, engage = true) } }
    post("/v1/kill/release") { call.serve(gateway, guardian) { killSwitch(gateway, engage = false) } }
}

/** Engages or releases the scope in the body; responds the switch as it now stands. */
private suspend fun ApplicationCall.killSwitch(
    gateway: Gateway,
    engage: Boolean,
) {
    val body =
        wireJson.parseToJsonElement(receiveText()) as? JsonObject
            ?: throw InvalidRequestException("body is not an object")
    val now = gateway.journal.killSwitch()
    val next =
        when (body["scope"]?.jsonPrimitive?.content) {
            "all" -> now.copy(all = engage)
            "symbols" -> {
                val symbols =
                    body["symbols"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: throw InvalidRequestException("symbols missing")
                now.copy(symbols = if (engage) (now.symbols + symbols).distinct() else now.symbols - symbols.toSet())
            }
            else -> throw InvalidRequestException("scope must be all or symbols")
        }
    gateway.journal.setKillSwitch(next)
    json(WireKillSwitch.serializer(), gateway.journal.killSwitch())
}

private fun ApplicationCall.id(): String = parameters["id"] ?: throw InvalidRequestException("client_order_id missing")

private fun ApplicationCall.param(name: String): Long =
    request.queryParameters[name]?.toLongOrNull() ?: throw InvalidRequestException("$name must be epoch milliseconds")

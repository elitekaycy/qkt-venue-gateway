package com.qkt.venued.host.server

import com.qkt.venued.adapter.VenueRefusedException
import com.qkt.venued.adapter.VenueUnavailableException
import com.qkt.venued.host.Gateway
import com.qkt.venued.host.orders.DeskResult
import com.qkt.venued.host.wire.InvalidRequestException
import com.qkt.vgp.WireError
import com.qkt.vgp.WireErrorBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The wire's JSON: defaults written, nulls kept, unknown fields ignored. */
internal val wireJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** Responds [value] as JSON with [status]. */
internal suspend fun <T> ApplicationCall.json(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(wireJson.encodeToString(serializer, value), ContentType.Application.Json, status)

/** Responds the VGP error envelope. */
internal suspend fun ApplicationCall.error(
    status: Int,
    code: String,
    message: String,
) = json(WireErrorBody.serializer(), WireErrorBody(WireError(code, message)), HttpStatusCode.fromValue(status))

/** Responds a desk refusal, or [ok] with the order. */
internal suspend fun ApplicationCall.desk(
    result: DeskResult,
    ok: suspend (DeskResult.Ok) -> Unit,
) = when (result) {
    is DeskResult.Ok -> ok(result)
    is DeskResult.Refused -> error(result.status, result.code, result.message)
}

/**
 * Runs [handler] for a caller holding one of [roles]; a missing or wrong token is `401`, and the
 * expected failures map to the wire's codes (`400` bad request, `422` venue refusal, `503` venue down).
 */
internal suspend fun ApplicationCall.serve(
    gateway: Gateway,
    roles: Set<Role>,
    handler: suspend ApplicationCall.() -> Unit,
) {
    val role = gateway.roleOf(request.headers["Authorization"])
    if (role == null || role !in roles) return error(401, "unauthorized", "missing, wrong or insufficient token")
    try {
        handler()
    } catch (e: InvalidRequestException) {
        error(400, "invalid_request", e.message ?: "invalid request")
    } catch (e: SerializationException) {
        error(400, "invalid_request", e.message ?: "malformed body")
    } catch (e: VenueRefusedException) {
        error(422, "venue_rejected", e.reason)
    } catch (e: VenueUnavailableException) {
        error(503, "venue_unavailable", e.message ?: "the venue cannot be reached")
    }
}

/** Every role. */
internal val ANY_ROLE = Role.entries.toSet()

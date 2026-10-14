package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket

/**
 * A WebSocket route at [path] for any caller holding a [gateway] token. A missing or wrong token is answered
 * `401 unauthorized` with the error envelope before the upgrade (wire spec §1), as on every REST route,
 * so a client tells a refused token from a dropped connection.
 */
internal fun Route.tokenWebSocket(
    gateway: Gateway,
    path: String,
    handler: suspend DefaultWebSocketServerSession.() -> Unit,
) = route(path) {
    install(
        createRouteScopedPlugin("TokenBeforeUpgrade") {
            onCall { call ->
                if (gateway.roleOf(call.request.headers["Authorization"]) == null) {
                    call.error(401, "unauthorized", "missing or wrong token")
                }
            }
        },
    )
    webSocket(handler = handler)
}

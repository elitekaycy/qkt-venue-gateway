package com.qkt.venued.host.server

import com.qkt.venued.host.Gateway
import com.qkt.vgp.WireEvent
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `GET /v1/stream?since=<seq>` (wire spec §4): every journaled event after `since`, oldest first, then
 * each new one as it is committed. `since` is exclusive; without it the stream is live only. A `since`
 * beyond the log (another stream's) or before the oldest retained event gets a `reset` first, so the
 * client resynchronizes from REST; a pruned log then replays what it still holds.
 */
internal fun Route.streamRoute(gateway: Gateway) {
    webSocket("/v1/stream") {
        if (gateway.roleOf(call.request.headers["Authorization"]) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val journal = gateway.journal
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val latest = journal.latestSeq()
        val oldest = journal.oldestSeq()
        var last = since ?: latest
        if (since != null && (since > latest || (oldest != null && since < oldest - 1))) {
            val reset = WireEvent(journal.stream, latest, "reset")
            send(Frame.Text(wireJson.encodeToString(WireEvent.serializer(), reset)))
            last = if (since > latest) latest else oldest!! - 1
        }
        gateway.appended.collect {
            while (true) {
                val page = withContext(Dispatchers.IO) { journal.eventsAfter(last) }
                if (page.isEmpty()) break
                page.forEach { send(Frame.Text(wireJson.encodeToString(WireEvent.serializer(), it))) }
                last = page.last().seq
            }
        }
    }
}

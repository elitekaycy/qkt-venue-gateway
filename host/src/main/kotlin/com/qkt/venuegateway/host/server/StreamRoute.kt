package com.qkt.venuegateway.host.server

import com.qkt.venuegateway.host.Gateway
import com.qkt.vgp.WireEvent
import io.ktor.server.routing.Route
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `GET /v1/stream?since=<seq>&stream=<id>` (wire spec §4): every journaled event after `since`, oldest
 * first, then each new one as it is committed. `since` is exclusive; without it the stream is live only.
 * A `since` from another stream (`stream` naming one that is not the journal's, or a `since` beyond the
 * log) or before the oldest retained event gets a `reset` first, so the client resynchronizes from REST;
 * a pruned log then replays what it still holds, another stream's `since` nothing. A missing or wrong
 * token is `401` before the upgrade.
 */
internal fun Route.streamRoute(gateway: Gateway) {
    tokenWebSocket(gateway, "/v1/stream") {
        val journal = gateway.journal
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val foreign = call.request.queryParameters["stream"]?.let { it != journal.stream } == true
        val latest = journal.latestSeq()
        val oldest = journal.oldestSeq()
        var last = since ?: latest
        if (since != null && (foreign || since > latest || (oldest != null && since < oldest - 1))) {
            val reset = WireEvent(journal.stream, latest, "reset")
            send(Frame.Text(wireJson.encodeToString(WireEvent.serializer(), reset)))
            last = if (foreign || since > latest) latest else oldest!! - 1
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

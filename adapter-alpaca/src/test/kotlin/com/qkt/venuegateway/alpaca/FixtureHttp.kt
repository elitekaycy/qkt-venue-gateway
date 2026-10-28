package com.qkt.venuegateway.alpaca

import com.qkt.venuegateway.alpaca.client.AlpacaHttp
import com.qkt.venuegateway.alpaca.client.AlpacaHttpException

/** One request the adapter made: its method, path, query and body. */
data class Sent(
    val method: String,
    val path: String,
    val params: List<Pair<String, String>>,
    val body: String?,
)

/**
 * An [AlpacaHttp] answering from recorded fixtures: [routes] maps `METHOD path` to a fixture name, or to
 * `<status>:<fixture>` for a recorded error answer; a route may be a list, answered in turn (the last repeats).
 */
class FixtureHttp(
    private val routes: Map<String, List<String>>,
) : AlpacaHttp {
    val sent = mutableListOf<Sent>()
    private val served = mutableMapOf<String, Int>()

    override fun get(
        path: String,
        params: List<Pair<String, String>>,
    ) = answer(Sent("GET", path, params, null))

    override fun send(
        method: String,
        path: String,
        body: String,
    ) = answer(Sent(method, path, emptyList(), body))

    override fun delete(path: String) = answer(Sent("DELETE", path, emptyList(), null))

    private fun answer(request: Sent): String {
        sent += request
        val key = "${request.method} ${request.path}"
        val list = routes[key] ?: error("no fixture route for $key")
        val n = served.merge(key, 1, Int::plus)!! - 1
        val route = list[n.coerceAtMost(list.size - 1)]
        if (route.isEmpty()) return ""
        val status = route.substringBefore(':', "").toIntOrNull()
        return if (status !=
            null
        ) {
            throw AlpacaHttpException(status, Fixtures.text(route.substringAfter(':')))
        } else {
            Fixtures.text(route)
        }
    }
}

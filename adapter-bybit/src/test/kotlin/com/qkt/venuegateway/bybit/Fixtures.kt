package com.qkt.venuegateway.bybit

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** Bybit's recorded answers (`src/test/resources/fixtures`), and a server that answers with them. */
object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java
            .getResource("/fixtures/$name")
            ?.readText()
            ?.trim() ?: error("no fixture $name")

    /**
     * A server answering each request with the fixture its [route] names for it (by path and query), and HTTP
     * 404 for a request no route names. Every request is kept in [requests], in order.
     */
    class Server(
        private val route: (path: String, query: Map<String, String>) -> String?,
    ) : AutoCloseable {
        val requests = mutableListOf<RecordedRequest>()
        private val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            synchronized(requests) { requests += request }
                            val url = request.url
                            val query = url.queryParameterNames.associateWith { url.queryParameter(it)!! }
                            val name = route(url.encodedPath, query) ?: return MockResponse(code = 404)
                            return MockResponse(body = text(name))
                        }
                    }
                start()
            }

        /** The server's base URL, without a trailing slash. */
        val url: String get() = server.url("/").toString().removeSuffix("/")

        override fun close() = server.close()
    }
}

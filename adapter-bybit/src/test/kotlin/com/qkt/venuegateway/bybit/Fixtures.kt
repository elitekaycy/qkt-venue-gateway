package com.qkt.venuegateway.bybit

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

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
                            val url = request.requestUrl!!
                            val query = url.queryParameterNames.associateWith { url.queryParameter(it)!! }
                            val name = route(url.encodedPath, query) ?: return MockResponse().setResponseCode(404)
                            return MockResponse().setBody(text(name))
                        }
                    }
                start()
            }

        /** The server's base URL, without a trailing slash. */
        val url: String get() = server.url("/").toString().removeSuffix("/")

        override fun close() = server.shutdown()
    }
}

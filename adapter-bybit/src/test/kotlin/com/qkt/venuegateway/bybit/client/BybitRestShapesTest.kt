package com.qkt.venuegateway.bybit.client

import java.io.IOException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitRestShapesTest {
    private val server = MockWebServer().apply { start() }
    private val rest = BybitRest(server.url("/").toString().removeSuffix("/"), "k", "s", { 1_791_240_000_000L })

    @AfterEach
    fun stop() = server.close()

    private fun answer(body: String) = server.enqueue(MockResponse(body = body))

    @Test
    fun `an http 403 is the ip limit, a failure with its status as the code`() {
        server.enqueue(MockResponse(code = 403, body = "denied"))

        assertThatThrownBy { rest.get("/v5/market/tickers", emptyList()) }
            .isInstanceOf(BybitException::class.java)
            .hasMessageContaining("HTTP 403 on /v5/market/tickers")
            .extracting { (it as BybitException).code }
            .isEqualTo(403)
    }

    @Test
    fun `an answer with no retCode, or one that is not a number, is refused as not bybit's`() {
        answer("""{"result":{}}""")
        answer("""{"retCode":"soon","result":{}}""")

        assertThatThrownBy { rest.get("/v5/a", emptyList()) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("without retCode")
        assertThatThrownBy { rest.get("/v5/b", emptyList()) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("without retCode")
    }

    @Test
    fun `a json answer that is no object is refused as having no json`() {
        answer("[1,2]")

        assertThatThrownBy { rest.get("/v5/a", emptyList()) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("without JSON")
    }

    @Test
    fun `a refusal naming no message carries an empty one`() {
        answer("""{"retCode":10001}""")

        assertThatThrownBy { rest.get("/v5/a", emptyList()) }.hasMessage("bybit 10001: ")
    }

    @Test
    fun `an answer with no time is stamped by the client's clock and one with no result has an empty one`() {
        answer("""{"retCode":0}""")

        val answered = rest.get("/v5/a", emptyList())

        assertThat(answered.timeMs).isEqualTo(1_791_240_000_000L)
        assertThat(answered.result).isEmpty()
    }

    @Test
    fun `a time that is no number falls back to the client's clock`() {
        answer("""{"retCode":0,"time":"later","result":{"list":[]}}""")

        assertThat(rest.get("/v5/a", emptyList()).timeMs).isEqualTo(1_791_240_000_000L)
    }
}

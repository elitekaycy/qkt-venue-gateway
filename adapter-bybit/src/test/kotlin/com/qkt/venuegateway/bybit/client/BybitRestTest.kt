package com.qkt.venuegateway.bybit.client

import com.qkt.venuegateway.bybit.Fixtures
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitRestTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().removeSuffix("/")
    private val rest = BybitRest(base, "the-key", "the-secret", { 1_791_240_000_000L })

    @AfterEach
    fun stop() = server.shutdown()

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("the-secret".toByteArray(), "HmacSHA256")) }
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `a signed get signs the exact query string it sends and answers the result with bybit's time`() {
        server.enqueue(MockResponse().setBody(Fixtures.text("private/executions-empty.json")))

        val answer = rest.get("/v5/execution/list", listOf("category" to "linear", "limit" to "5"), signed = true)

        val sent = server.takeRequest()
        assertThat(sent.requestUrl!!.encodedQuery).isEqualTo("category=linear&limit=5")
        assertThat(sent.getHeader("X-BAPI-API-KEY")).isEqualTo("the-key")
        assertThat(sent.getHeader("X-BAPI-TIMESTAMP")).isEqualTo("1791240000000")
        assertThat(sent.getHeader("X-BAPI-RECV-WINDOW")).isEqualTo("5000")
        assertThat(sent.getHeader("X-BAPI-SIGN")).isEqualTo(hmac("1791240000000the-key5000category=linear&limit=5"))
        assertThat(answer.timeMs).isEqualTo(1_791_240_179_286L)
        assertThat(answer.result["category"].toString()).isEqualTo("\"linear\"")
    }

    @Test
    fun `a page cursor is sent as bybit wrote it, never encoded again`() {
        server.enqueue(MockResponse().setBody(Fixtures.text("instruments-linear-page-2.json")))

        rest.get("/v5/market/instruments-info", listOf("cursor" to "first%3D0GUSDT%26last%3D1000000BABYDOGEUSDT"))

        assertThat(
            server.takeRequest().requestUrl!!.encodedQuery,
        ).isEqualTo("cursor=first%3D0GUSDT%26last%3D1000000BABYDOGEUSDT")
        assertThat(server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun `a post signs its body and sends it as json`() {
        server.enqueue(MockResponse().setBody(Fixtures.text("private/cancel-unknown.json")))
        val body = buildJsonObject { put("category", "linear") }

        assertThatThrownBy { rest.post("/v5/order/cancel", body) }.isInstanceOf(BybitException::class.java)

        val sent = server.takeRequest()
        assertThat(sent.body.readUtf8()).isEqualTo("""{"category":"linear"}""")
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json")
        assertThat(sent.getHeader("X-BAPI-SIGN")).isEqualTo(hmac("""1791240000000the-key5000{"category":"linear"}"""))
    }

    @Test
    fun `a non-zero retCode is bybit's refusal with its code and message`() {
        server.enqueue(MockResponse().setBody(Fixtures.text("private/error-qty-step.json")))

        assertThatThrownBy { rest.post("/v5/order/create", buildJsonObject {}) }
            .isInstanceOf(BybitException::class.java)
            .hasMessage("bybit 10001: Qty invalid")
            .extracting { (it as BybitException).code }
            .isEqualTo(10001)
    }

    @Test
    fun `a rate limit or server failure without an answer is its http status`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(502))

        assertThatThrownBy {
            rest.get("/v5/market/tickers", emptyList())
        }.extracting { (it as BybitException).code }.isEqualTo(429)
        assertThatThrownBy {
            rest.get("/v5/market/tickers", emptyList())
        }.extracting { (it as BybitException).code }.isEqualTo(502)
    }

    @Test
    fun `an answer that is not bybit's json is the venue being unreachable`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>not found</html>"))

        assertThatThrownBy { rest.get("/v5/nowhere", emptyList()) }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a client built without a key refuses to sign rather than send unsigned`() {
        assertThatThrownBy { BybitRest(base).get("/v5/execution/list", emptyList(), signed = true) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(BybitRest(base, "k", "s").toString()).doesNotContain("s\"").isEqualTo("BybitRest($base)")
    }
}

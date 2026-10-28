package com.qkt.venuegateway.support

import java.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JsonHttpTest {
    private val server = MockWebServer()

    @AfterEach
    fun stop() = server.shutdown()

    private fun http() =
        JsonHttp(server.url("").toString().trimEnd('/'), timeoutMs = 2_000) { request, method, path, payload ->
            request.header("X-Signed", "$method $path $payload")
        }

    @Test
    fun `a venue's error status comes back as its answer, not as an exception`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"code":40310000,"message":"insufficient buying power"}"""),
        )
        val answer = http().post("/v2/orders", buildJsonObject { put("symbol", JsonPrimitive("AAPL")) })
        assertThat(answer.status).isEqualTo(403)
        assertThat(answer.unavailable).isFalse
        assertThat(answer.json().jsonObject["message"]).isEqualTo(JsonPrimitive("insufficient buying power"))
        val sent = server.takeRequest()
        assertThat(sent.getHeader("X-Signed")).isEqualTo("""POST /v2/orders {"symbol":"AAPL"}""")
    }

    @Test
    fun `query parameters are sent in order and signed as sent`() {
        server.enqueue(MockResponse().setBody("[]"))
        http().get("/v2/orders", listOf("status" to "open", "limit" to "500"))
        val sent = server.takeRequest()
        assertThat(sent.path).isEqualTo("/v2/orders?status=open&limit=500")
        assertThat(sent.getHeader("X-Signed")).isEqualTo("GET /v2/orders status=open&limit=500")
    }

    @Test
    fun `429 and 5xx are the venue being unavailable, and a non-JSON body names the call`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>busy</html>"))
        val answer = http().delete("/v2/orders/1")
        assertThat(answer.unavailable).isTrue
        assertThatThrownBy { answer.json() }
            .isInstanceOf(
                IOException::class.java,
            ).hasMessageContaining("DELETE /v2/orders/1")
    }

    @Test
    fun `an unreachable venue is an IOException`() {
        server.shutdown()
        assertThatThrownBy { http().get("/v2/clock") }.isInstanceOf(IOException::class.java)
    }
}

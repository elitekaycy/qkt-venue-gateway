package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiClientArmsTest {
    private val server = MockWebServer().apply { start() }
    private val rest = KalshiRest(server.url("/trade-api/v2").toString())

    @AfterEach
    fun stop() = server.close()

    private fun answer(
        code: Int,
        body: String,
    ) = server.enqueue(MockResponse(code = code, body = body))

    @Test
    fun `a successful answer that is no object is refused as having none`() {
        answer(200, "[1]")

        assertThatThrownBy { rest.get("/exchange/status") }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("GET /exchange/status without an object")
    }

    @Test
    fun `a refusal is named by kalshi's code and words, or by its status and the call when it gave none`() {
        val recorded = Fixtures.json("private/place-funds.json")
        val error = recorded["error"]!!.jsonObject
        answer(400, JsonObject(mapOf("error" to JsonObject(error - "code"))).toString())
        answer(400, JsonObject(mapOf("error" to JsonObject(mapOf("code" to error["code"]!!)))).toString())
        answer(400, JsonObject(mapOf("error" to JsonPrimitive("bad"))).toString())
        answer(400, "[1]")
        answer(502, "<html>bad gateway</html>")

        val noCode = refusal()
        val noWords = refusal()
        val textError = refusal()
        val listBody = refusal()
        val htmlBody = refusal()

        assertThat(noCode.code).isEqualTo("http_400")
        assertThat(noWords.code).isEqualTo(error["code"]!!.toString().trim('"'))
        assertThat(noWords.message).endsWith("GET /portfolio/balance")
        listOf(textError, listBody).forEach { assertThat(it.code).isEqualTo("http_400") }
        assertThat(htmlBody.code).isEqualTo("http_502")
    }

    private fun refusal(): KalshiException =
        runCatching { rest.get("/portfolio/balance") }.exceptionOrNull() as KalshiException

    @Test
    fun `the portfolio follows kalshi's page cursor to the last page`() {
        val first = Fixtures.json("private/positions.json")
        answer(200, JsonObject(first + ("cursor" to JsonPrimitive("page-2"))).toString())
        answer(200, Fixtures.text("private/positions.json"))

        val held = KalshiPortfolio(rest).positions()

        assertThat(server.takeRequest().url.queryParameter("cursor")).isNull()
        assertThat(server.takeRequest().url.queryParameter("cursor")).isEqualTo("page-2")
        assertThat(held).hasSize(2 * first["market_positions"]!!.jsonArray.size)
    }

    @Test
    fun `a position naming no exchange shard reads as none`() {
        val row =
            Fixtures
                .json("private/positions.json")["market_positions"]!!
                .jsonArray
                .first()
                .jsonObject

        assertThat(KalshiPortfolioJson.position(JsonObject(row - "exchange_index")).exchangeIndex).isNull()
        assertThat(KalshiPortfolioJson.position(row).exchangeIndex).isEqualTo(0)
    }

    @Test
    fun `a pkcs1 key too short to be a key is wrapped, then refused as no key`() {
        val body =
            java.util.Base64
                .getEncoder()
                .encodeToString(ByteArray(16) { 1 })
        val pem = "-----BEGIN RSA PRIVATE KEY-----\n$body\n-----END RSA PRIVATE KEY-----\n"

        assertThatThrownBy { KalshiAuth("key-id", pem) }.isInstanceOf(Exception::class.java)
    }
}

package com.qkt.venuegateway.deribit.client

import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The public client's order book and envelope check over recorded answers, and what a market that only lists and
 * quotes answers for the reads it does not make.
 */
class DeribitPublicEdgesTest {
    private val asked = CopyOnWriteArrayList<RecordedRequest>()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    /** The recorded answer's result without its JSON-RPC envelope, as a proxy that strips it would serve. */
    private fun bareResult(name: String) = Json.parseToJsonElement(fixture(name)).jsonObject["result"].toString()

    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        asked += request
                        val body =
                            when (request.url.encodedPath.substringAfterLast('/')) {
                                "get_order_book" -> fixture("order-book-perp.json")
                                else -> bareResult("instruments-future.json")
                            }
                        return MockResponse(body = body)
                    }
                }
            start()
        }
    private val client = DeribitPublicClient(server.url("/").toString().trimEnd('/'))

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `an order book is asked by instrument and depth and read with every level exact`() {
        val book = client.orderBook("BTC_USDC-PERPETUAL", 10)

        val url = asked.single().url
        assertThat(url.encodedPath).isEqualTo("/api/v2/public/get_order_book")
        assertThat(url.queryParameter("instrument_name")).isEqualTo("BTC_USDC-PERPETUAL")
        assertThat(url.queryParameter("depth")).isEqualTo("10")
        assertThat(book.name).isEqualTo("BTC_USDC-PERPETUAL")
        assertThat(book.timestampMs).isEqualTo(1_791_171_007_549L)
        val bid = book.bids.first()
        val ask = book.asks.first()
        assertThat(bid.first.toPlainString()).isEqualTo("86472.7")
        assertThat(bid.second).isEqualByComparingTo("10")
        assertThat(ask.first.toPlainString()).isEqualTo("86472.8")
    }

    @Test
    fun `an answer that is not a JSON-RPC envelope is refused naming the method`() {
        assertThatThrownBy { client.instruments("USDC", "future") }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("get_instruments")
            .hasMessageContaining("not a JSON-RPC envelope")
    }

    @Test
    fun `a market that does not read books, funding, trades or the tape says so for each`() {
        val market =
            object : DeribitMarketData {
                override fun instruments(
                    currency: String,
                    kind: String,
                ) = emptyList<DeribitInstrument>()

                override fun instrument(name: String) = error("unused")

                override fun ticker(name: String): DeribitTicker = error("unused")

                override fun klines(
                    name: String,
                    minutes: Long,
                    fromMs: Long,
                    toMs: Long,
                ) = emptyList<DeribitKline>()

                override fun deliveryPrices(
                    index: String,
                    count: Int,
                ) = emptyList<Pair<LocalDate, BigDecimal>>()
            }
        val name = "BTC_USDC-PERPETUAL"

        assertThatThrownBy { market.orderBook(name, 10) }.hasMessageContaining("order books")
        assertThatThrownBy { market.fundingRates(name, 0, 1) }.hasMessageContaining("funding rates")
        assertThatThrownBy { market.edgeTrade(name, 0, 1, newest = true) }.hasMessageContaining("does not read trades")
        assertThatThrownBy { market.tradesFrom(name, 0, 1, 10) }.hasMessageContaining("does not read trades")
        assertThatThrownBy { market.tape(name, 0, 1, 10) }.hasMessageContaining("trade tape")
    }
}

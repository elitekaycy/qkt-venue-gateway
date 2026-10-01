package com.qkt.venuegateway.deribit.client

import java.math.BigDecimal
import java.time.LocalDate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Real Deribit public responses (recorded 2026-10-01 under `fixtures/`), parsed exactly. */
class DeribitPublicClientTest {
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val url = request.requestUrl!!
                        val file =
                            when (url.encodedPath.substringAfterLast('/')) {
                                "get_instruments" ->
                                    if (url.queryParameter("kind") ==
                                        "option"
                                    ) {
                                        "instruments-option"
                                    } else {
                                        "instruments-future"
                                    }
                                "ticker" ->
                                    if (url
                                            .queryParameter(
                                                "instrument_name",
                                            )!!
                                            .startsWith("HYPE")
                                    ) {
                                        "ticker-no-bid"
                                    } else {
                                        "ticker-option"
                                    }
                                "get_tradingview_chart_data" -> "chart-1m"
                                "get_delivery_prices" -> "delivery-prices"
                                "get_instrument" -> "instrument-expired"
                                else -> return MockResponse().setBody(
                                    """{"jsonrpc":"2.0","error":{"code":13009,"message":"bad"}}""",
                                )
                            }
                        return MockResponse().setBody(javaClass.getResource("/fixtures/$file.json")!!.readText())
                    }
                }
            start()
        }
    private val client = DeribitPublicClient(server.url("/").toString().trimEnd('/'))

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `perpetuals are told from dated futures, and tiny tick sizes stay exact`() {
        val futures = client.instruments("USDC", "future").associateBy { it.name }

        assertThat(futures.getValue("BTC_USDC-PERPETUAL").perpetual).isTrue()
        assertThat(futures.getValue("BTC_USDC-PERPETUAL").expiryMs).isNull()
        assertThat(futures.getValue("BTC_USDC-2OCT26").perpetual).isFalse()
        assertThat(futures.getValue("BTC_USDC-2OCT26").expiryMs).isNotNull()
        assertThat(futures.getValue("1000BONK_USDC-PERPETUAL").tickSize.toPlainString()).isEqualTo("0.000001")
        val option = client.instruments("USDC", "option").first()
        assertThat(option.strike).isEqualByComparingTo("65000")
        assertThat(option.optionType).isEqualTo("call")
        assertThat(option.priceIndex).isEqualTo("btc_usdc")
        val expired = client.instrument("BTC_USDC-30SEP26")
        assertThat(expired.expiryMs).isNotNull()
        assertThat(expired.contractSize).isEqualByComparingTo("0.0001")
    }

    @Test
    fun `a ticker keeps its book and option values, and a zero side is no side`() {
        val option = client.ticker("BTC_USDC-2OCT26-65000-C")
        val noBid = client.ticker("HYPE_USDC-2OCT26-65-P")

        assertThat(option.bid).isEqualByComparingTo("18740")
        assertThat(option.askAmount).isEqualByComparingTo("14")
        assertThat(option.markIv).isEqualByComparingTo("73.57")
        assertThat(option.underlyingPrice).isEqualByComparingTo("83987.8")
        assertThat(noBid.bid).isNull()
        assertThat(noBid.bidAmount).isNull()
        assertThat(noBid.ask).isEqualByComparingTo("0.012")
    }

    @Test
    fun `klines and delivery prices read back as recorded, and an unsupported kline length is refused`() {
        val klines = client.klines("BTC_USDC-PERPETUAL", 1, 0, 1)
        val delivery = client.deliveryPrices("btc_usdc", 3)

        assertThat(klines).hasSize(6)
        assertThat(klines.first().startMs).isEqualTo(1_790_863_980_000L)
        assertThat(klines.zipWithNext().all { (a, b) -> b.startMs - a.startMs == 60_000L }).isTrue()
        assertThat(delivery.first()).isEqualTo(LocalDate.parse("2026-10-01") to BigDecimal("83495.91"))
        assertThatThrownBy { client.klines("X", 7, 0, 1) }.hasMessageContaining("7-minute")
    }
}

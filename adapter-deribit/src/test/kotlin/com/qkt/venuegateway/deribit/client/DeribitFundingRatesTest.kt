package com.qkt.venuegateway.deribit.client

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Deribit's funding history (`funding-rate-history.json`, recorded on testnet 2026-10-04), read exactly. */
class DeribitFundingRatesTest {
    private val hour = 3_600_000L
    private val asked = CopyOnWriteArrayList<Pair<Long, Long>>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val url = request.requestUrl!!
                        asked += url.queryParameter("start_timestamp")!!.toLong() to
                            url.queryParameter("end_timestamp")!!.toLong()
                        return MockResponse().setBody(
                            javaClass.getResource("/fixtures/funding-rate-history.json")!!.readText(),
                        )
                    }
                }
            start()
        }
    private val client = DeribitPublicClient(server.url("/").toString().trimEnd('/'))

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `each hour's rate and index are read from their literal text, oldest first`() {
        val rates = client.fundingRates("SOL_USDC-PERPETUAL", 1_791_122_400_000L, 1_791_129_600_000L)

        assertThat(
            rates.map { it.timestampMs },
        ).containsExactly(1_791_122_400_000L, 1_791_126_000_000L, 1_791_129_600_000L)
        assertThat(rates[0].interest1h.toPlainString()).isEqualTo("-0.000020390691060973714")
        assertThat(rates[1].interest1h.toPlainString()).isEqualTo("0.00004182650335544141")
        assertThat(rates[1].indexPrice.toPlainString()).isEqualTo("121.8285")
        assertThat(rates[2].interest1h.signum()).isZero
    }

    @Test
    fun `a long range is asked for in spans Deribit answers whole, and rows outside a span are not taken from it`() {
        val from = 1_791_122_400_000L - 1_000 * hour

        val rates = client.fundingRates("SOL_USDC-PERPETUAL", from, 1_791_129_600_000L)

        assertThat(asked).containsExactly(from to from + 720 * hour - 1, from + 720 * hour to 1_791_129_600_000L)
        assertThat(rates.map { it.timestampMs }).doesNotHaveDuplicates().hasSize(3)
    }
}

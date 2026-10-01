package com.qkt.venuegateway.deribit.client

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Deribit answers a kline request with at most 5001 klines, the newest, and `status: ok` (measured on
 * testnet 2026-10-01: 14 days of 1-minute klines came back as the last 3.5 days). This Deribit does the
 * same: it returns the klines from the one holding `start_timestamp` to `end_timestamp`, cut to the last
 * 5001, and `no_data` when there are none.
 */
class DeribitKlineChunksTest {
    private val minute = 60_000L
    private val listedFrom = 1_000L * minute
    private val asked = CopyOnWriteArrayList<Long>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val url = request.requestUrl!!
                        val step = url.queryParameter("resolution")!!.toLong() * minute
                        val start = url.queryParameter("start_timestamp")!!.toLong()
                        val end = url.queryParameter("end_timestamp")!!.toLong()
                        val starts =
                            (
                                maxOf(
                                    start / step * step,
                                    listedFrom,
                                )..end step step
                            ).toList().takeLast(DERIBIT_CAP)
                        asked += (end - start) / step
                        if (starts.isEmpty()) {
                            return MockResponse().setBody(
                                javaClass.getResource("/fixtures/chart-no-data.json")!!.readText(),
                            )
                        }

                        fun col(v: String) = starts.joinToString(",") { v }
                        val body =
                            """{"jsonrpc":"2.0","result":{"status":"ok","ticks":[${starts.joinToString(",")}],""" +
                                """"open":[${col(
                                    "1",
                                )}],"high":[${col("2")}],"low":[${col("1")}],"close":[${col("2")}],""" +
                                """"volume":[${col("1")}],"cost":[${col("1")}]}}"""
                        return MockResponse().setBody(body)
                    }
                }
            start()
        }
    private val client = DeribitPublicClient(server.url("/").toString().trimEnd('/'))

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `a range longer than one deribit answer comes back whole, in order, each kline once`() {
        val from = listedFrom + 7 * minute
        val to = from + 20_160 * minute

        val klines = client.klines("BTC_USDC-PERPETUAL", 1, from, to)

        assertThat(klines.first().startMs).isEqualTo(from)
        assertThat(klines.last().startMs).isEqualTo(to)
        assertThat(klines).hasSize(20_161)
        assertThat(klines.zipWithNext().all { (a, b) -> b.startMs - a.startMs == minute }).isTrue()
        assertThat(asked.max()).isLessThan(DERIBIT_CAP.toLong())
    }

    @Test
    fun `a range from before the listing starts at the venue's first kline, and an empty range is empty`() {
        val klines = client.klines("BTC_USDC-PERPETUAL", 1, 0, listedFrom + 9 * minute)

        assertThat(klines.map { it.startMs }).containsExactlyElementsOf((0L..9L).map { listedFrom + it * minute })
        assertThat(client.klines("BTC_USDC-PERPETUAL", 1, 0, listedFrom - minute)).isEmpty()
    }

    private companion object {
        const val DERIBIT_CAP = 5001
    }
}

package com.qkt.venuegateway.deribit.client

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Deribit's public trade history (fixtures `trades-*.json`, `tape-*.json`, recorded on testnet and mainnet history), read exactly. */
class DeribitTradesTest {
    private val asked = CopyOnWriteArrayList<HttpUrl>()

    @Volatile private var fixture = ""
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        asked += request.requestUrl!!
                        return MockResponse().setBody(javaClass.getResource("/fixtures/$fixture")!!.readText())
                    }
                }
            start()
        }
    private val client = DeribitPublicClient(server.url("/").toString().trimEnd('/'))
    private val from = 1_791_154_800_000L
    private val to = 1_791_154_979_999L

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `the first trade of a range carries its sequence, time, mark and index as Deribit wrote them`() {
        fixture = "trades-first-in-range.json"

        val first = client.edgeTrade("BTC_USDC-PERPETUAL", from, to, newest = false)!!

        assertThat(first.seq).isEqualTo(14_316_645L)
        assertThat(first.timestampMs).isEqualTo(1_791_154_801_271L)
        assertThat(first.mark!!.toPlainString()).isEqualTo("86462.46")
        assertThat(first.index!!.toPlainString()).isEqualTo("86429.73")
        val url = asked.single()
        assertThat(url.encodedPath).endsWith("/public/get_last_trades_by_instrument_and_time")
        assertThat(url.queryParameter("start_timestamp")).isEqualTo("$from")
        assertThat(url.queryParameter("end_timestamp")).isEqualTo("$to")
        assertThat(url.queryParameter("count")).isEqualTo("1")
        assertThat(url.queryParameter("sorting")).isEqualTo("asc")
    }

    @Test
    fun `the last trade of a range is asked newest first`() {
        fixture = "trades-last-in-range.json"

        val last = client.edgeTrade("BTC_USDC-PERPETUAL", from, to, newest = true)!!

        assertThat(last.seq).isEqualTo(14_316_825L)
        assertThat(last.mark!!.toPlainString()).isEqualTo("86412.98")
        assertThat(asked.single().queryParameter("sorting")).isEqualTo("desc")
    }

    @Test
    fun `a range without a trade has no edge trade`() {
        fixture = "trades-none-in-range.json"

        assertThat(client.edgeTrade("BTC_USDC-PERPETUAL", 1_700_000_000_000L, 1_700_000_059_999L, newest = false))
            .isNull()
    }

    @Test
    fun `a page of trades comes in time order with whether more follow, both ends included`() {
        fixture = "trades-from-time.json"

        val page = client.tradesFrom("BTC_USDC-PERPETUAL", from, to, 1_000)

        assertThat(page.items).hasSize(181)
        assertThat(page.more).isFalse
        assertThat(page.items.map { it.seq }).isEqualTo((14_316_645L..14_316_825L).toList())
        val url = asked.single()
        assertThat(url.encodedPath).endsWith("/public/get_last_trades_by_instrument_and_time")
        assertThat(url.queryParameter("count")).isEqualTo("1000")
        assertThat(url.queryParameter("sorting")).isEqualTo("asc")
    }

    @Test
    fun `a page cut at its count says more follow, its millisecond's trades out of sequence order`() {
        fixture = "trades-from-time-history-cut.json"

        val page = client.tradesFrom("BTC_USDC-PERPETUAL", 1_790_993_684_372L, 1_790_993_698_636L, 5)

        assertThat(page.more).isTrue
        assertThat(
            page.items.map { it.seq },
        ).containsExactly(13_987_640L, 13_987_642L, 13_987_641L, 13_987_643L, 13_987_647L)
    }

    @Test
    fun `mainnet's history host answers in the same shape, with the mark and index`() {
        fixture = "trades-history-mainnet.json"

        val last = client.edgeTrade("BTC_USDC-PERPETUAL", 1_759_622_520_000L, 1_759_622_579_999L, newest = true)!!

        assertThat(last.timestampMs).isEqualTo(1_759_622_524_558L)
        assertThat(last.mark!!.toPlainString()).isEqualTo("122503.44")
        assertThat(last.index!!.toPlainString()).isEqualTo("122450.62")
    }

    @Test
    fun `more than a thousand trades a call is refused by Deribit`() {
        fixture = "trades-count-too-high.json"

        assertThatThrownBy { client.tradesFrom("BTC_USDC-PERPETUAL", 1L, 2L, 1_001) }
            .isInstanceOf(DeribitException::class.java)
            .hasMessageContaining("-32602")
    }

    @Test
    fun `a page of the tape carries each print's id, price, amount, taker side and liquidation mark`() {
        fixture = "tape-liquidation-taker.json"

        val page = client.tape("BTC_USDC-PERPETUAL", 1_791_096_880_272L, 1_791_096_884_272L, 20)

        assertThat(page.more).isTrue
        assertThat(page.items).hasSize(20)
        val liquidation = page.items.single { it.liquidation != null }
        assertThat(liquidation.tradeId).isEqualTo("USDC-65965358")
        assertThat(liquidation.liquidation).isEqualTo("T")
        assertThat(liquidation.direction).isEqualTo("buy")
        assertThat(liquidation.amount.toPlainString()).isEqualTo("0.0011")
        assertThat(liquidation.price.toPlainString()).isEqualTo("85070.2")
        val url = asked.single()
        assertThat(url.encodedPath).endsWith("/public/get_last_trades_by_instrument_and_time")
        assertThat(url.queryParameter("end_timestamp")).isEqualTo("1791096884272")
        assertThat(url.queryParameter("count")).isEqualTo("20")
        assertThat(url.queryParameter("sorting")).isEqualTo("asc")
    }
}

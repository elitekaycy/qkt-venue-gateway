package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitMarksTest {
    private val server =
        Fixtures.Server { path, query ->
            when (path to query["interval"]) {
                "/v5/market/mark-price-kline" to "1" -> "mark-kline-1m-15.json"
                "/v5/market/index-price-kline" to "1" -> "index-kline-1m-15.json"
                else -> null
            }
        }
    private val marks = BybitMarks(BybitPublicClient(BybitRest(server.url)), "linear")
    private val from = 1_791_240_180_000L
    private val to = 1_791_241_080_000L

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a window that is not whole minutes dividing a day is refused`() {
        assertThatThrownBy { marks.sampled("BTCUSDT", 90_000, from, to, to) }
            .isInstanceOf(VenueRefusedException::class.java)
        assertThatThrownBy { marks.sampled("BTCUSDT", 7 * 60_000, from, to, to) }
            .isInstanceOf(VenueRefusedException::class.java)
    }

    @Test
    fun `marks are each window's closing mark and index, at its last millisecond`() {
        val sampled = marks.sampled("BTCUSDT", 60_000, from, to, to)

        assertThat(sampled).hasSize(15)
        assertThat(sampled.first().timeMs).isEqualTo(from + 59_999)
        assertThat(sampled.map { it.timeMs / 60_000 }).doesNotHaveDuplicates()
        assertThat(sampled.all { it.mark != null && it.index != null }).isTrue
        val mark = Fixtures.text("mark-kline-1m-15.json")
        assertThat(mark).contains("\"${sampled.last().mark!!.toPlainString()}\"]")
    }

    @Test
    fun `the window holding now is still forming and its marks are never served`() {
        val sampled = marks.sampled("BTCUSDT", 60_000, from, to, 1_791_240_330_000L)

        assertThat(sampled.map { it.timeMs }).containsExactly(from + 59_999, from + 119_999)
    }
}

package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitBarsTest {
    private val server =
        Fixtures.Server { path, query ->
            when (path to query["interval"]) {
                "/v5/market/kline" to "1" -> "kline-linear-1m-15.json"
                "/v5/market/kline" to "5" -> "kline-linear-5m.json"
                "/v5/market/mark-price-kline" to "1" -> "mark-kline-1m-15.json"
                "/v5/market/index-price-kline" to "1" -> "index-kline-1m-15.json"
                else -> null
            }
        }
    private val bars = BybitBars(BybitPublicClient(BybitRest(server.url)), "linear")
    private val from = 1_791_240_180_000L
    private val to = 1_791_241_080_000L

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a window bybit serves is read as its own klines`() {
        val served = bars.closed("BTCUSDT", 300_000, from, to, to)

        assertThat(
            server.requests
                .single()
                .requestUrl!!
                .queryParameter("interval"),
        ).isEqualTo("5")
        assertThat(served.map { it.startMs }).containsExactly(1_791_240_300_000L, 1_791_240_600_000L)
    }

    @Test
    fun `another window is built from the longest bybit interval dividing it, open to close`() {
        val oneMinute = bars.closed("BTCUSDT", 60_000, from, to, to)
        val twoMinute = bars.closed("BTCUSDT", 120_000, from, to, to)

        assertThat(twoMinute.first().startMs).isEqualTo(1_791_240_240_000L)
        val parts = oneMinute.filter { it.startMs in 1_791_240_240_000L until 1_791_240_360_000L }
        val bar = twoMinute.first()
        assertThat(bar.open).isEqualTo(parts.first().open)
        assertThat(bar.close).isEqualTo(parts.last().close)
        assertThat(bar.high).isEqualTo(parts.maxOf { it.high })
        assertThat(bar.low).isEqualTo(parts.minOf { it.low })
        assertThat(bar.volume).isEqualByComparingTo(parts[0].volume + parts[1].volume)
    }

    @Test
    fun `the bar holding now is still forming and is never served`() {
        val now = 1_791_240_330_000L

        val served = bars.closed("BTCUSDT", 60_000, from, to, now)

        assertThat(served.last().startMs + 60_000).isLessThanOrEqualTo(now)
        assertThat(served.map { it.startMs }).containsExactly(1_791_240_180_000L, 1_791_240_240_000L)
    }

    @Test
    fun `a window that is not whole minutes dividing a day is refused`() {
        assertThatThrownBy {
            bars.closed(
                "BTCUSDT",
                90_000,
                from,
                to,
                to,
            )
        }.isInstanceOf(VenueRefusedException::class.java)
        assertThatThrownBy {
            bars.closed(
                "BTCUSDT",
                7 * 60_000,
                from,
                to,
                to,
            )
        }.isInstanceOf(VenueRefusedException::class.java)
    }

    @Test
    fun `marks are each window's closing mark and index, at its last millisecond`() {
        val marks = bars.marks("BTCUSDT", 60_000, from, to, to)

        assertThat(marks).hasSize(15)
        assertThat(marks.first().timeMs).isEqualTo(from + 59_999)
        assertThat(marks.map { it.timeMs / 60_000 }).doesNotHaveDuplicates()
        assertThat(marks.all { it.mark != null && it.index != null }).isTrue
        val mark = Fixtures.text("mark-kline-1m-15.json")
        assertThat(mark).contains("\"${marks.last().mark!!.toPlainString()}\"]")
    }
}

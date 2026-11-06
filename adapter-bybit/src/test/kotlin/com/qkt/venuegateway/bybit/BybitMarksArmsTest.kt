package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.bybit.client.BybitPublicClient
import com.qkt.venuegateway.bybit.client.BybitRest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class BybitMarksArmsTest {
    private var mark = "mark-kline-1m-15.json"
    private var index = "index-kline-1m-15.json"
    private val server =
        Fixtures.Server { path, _ ->
            when (path) {
                "/v5/market/mark-price-kline" -> mark
                "/v5/market/index-price-kline" -> index
                else -> null
            }
        }
    private val marks = BybitMarks(BybitPublicClient(BybitRest(server.url)), "linear")
    private val from = 1_791_240_180_000L
    private val to = 1_791_241_080_000L

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a window of zero or less is refused`() {
        assertThatThrownBy { marks.sampled("BTCUSDT", 0, from, to, to) }
            .isInstanceOf(VenueRefusedException::class.java)
        assertThatThrownBy { marks.sampled("BTCUSDT", -60_000, from, to, to) }
            .isInstanceOf(VenueRefusedException::class.java)
    }

    @Test
    fun `a range whose first window has not closed yet has no marks and asks bybit for none`() {
        assertThat(marks.sampled("BTCUSDT", 60_000, from, to, from)).isEmpty()
        assertThat(server.requests).isEmpty()
    }

    @Test
    fun `a window the index has but the mark lacks, or the other way, carries the one it has`() {
        mark = "mark-kline-empty.json"
        val noMark = marks.sampled("BTCUSDT", 60_000, from, to, to)
        mark = "mark-kline-1m-15.json"
        index = "mark-kline-empty.json"
        val noIndex = marks.sampled("BTCUSDT", 60_000, from, to, to)

        assertThat(noMark).hasSize(15)
        assertThat(noMark.all { it.mark == null && it.index != null }).isTrue
        assertThat(noIndex).hasSize(15)
        assertThat(noIndex.all { it.mark != null && it.index == null }).isTrue
    }

    @Test
    fun `klines a venue sends outside the range asked are dropped, so only the windows inside it are marked`() {
        val sampled = marks.sampled("BTCUSDT", 60_000, from + 120_000, from + 300_000, to)

        assertThat(sampled.map { it.timeMs }).containsExactly(
            from + 179_999,
            from + 239_999,
            from + 299_999,
        )
    }
}

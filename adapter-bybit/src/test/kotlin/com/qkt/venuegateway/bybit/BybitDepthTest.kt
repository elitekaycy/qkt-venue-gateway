package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.bybit.client.BybitBook
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitDepthTest {
    @TempDir lateinit var dir: Path

    private val result =
        Json
            .parseToJsonElement(
                Fixtures.text("orderbook-linear.json"),
            ).jsonObject["result"]!!
            .jsonObject
    private val book = BybitMarketJson.book(result, 1_791_239_695_194L)
    private var reads = 0

    private fun depth(
        now: Long,
        answer: BybitBook = book,
    ) = BybitDepth({
        reads++
        answer
    }, dir, { now }, 0)

    @Test
    fun `a read reaching now records the book at bybit's stamp and serves it`() {
        val served = depth(1_791_239_700_000).read("BTCUSDT", 1_791_239_000_000, 1_791_239_700_000)

        assertThat(served).hasSize(1)
        assertThat(served.single().timeMs).isEqualTo(1_791_239_695_194L)
        assertThat(
            served
                .single()
                .bids
                .first()
                .price,
        ).isEqualByComparingTo("87717.30")
        assertThat(
            served
                .single()
                .asks
                .first()
                .amount,
        ).isEqualByComparingTo("0.009")
    }

    @Test
    fun `a read of the past serves what was recorded without asking bybit`() {
        depth(1_791_239_700_000).read("BTCUSDT", 1_791_239_000_000, 1_791_239_700_000)
        reads = 0

        val served = depth(1_791_249_700_000).read("BTCUSDT", 1_791_239_000_000, 1_791_239_700_000)

        assertThat(reads).isZero
        assertThat(served).hasSize(1)
    }

    @Test
    fun `the same snapshot read twice is recorded once, and a newer one adds to the series`() {
        val first = depth(1_791_239_700_000)
        first.read("BTCUSDT", 1_791_239_000_000, 1_791_239_700_000)
        first.read("BTCUSDT", 1_791_239_000_000, 1_791_239_700_000)
        val newer = book.copy(timeMs = 1_791_239_699_000)

        val served = depth(1_791_239_700_000, newer).read("BTCUSDT", 1_791_239_000_000, 1_791_239_700_000)

        assertThat(served.map { it.timeMs }).containsExactly(1_791_239_695_194L, 1_791_239_699_000L)
    }
}

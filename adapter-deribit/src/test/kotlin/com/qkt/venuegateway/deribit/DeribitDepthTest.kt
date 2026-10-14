package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.VenueDepth
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitOrderBook
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What the gateway records and serves of Deribit's order book, which Deribit publishes only as it stands. */
class DeribitDepthTest {
    private fun recorded(fixture: String): DeribitOrderBook =
        DeribitJson.orderBook(
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/$fixture")!!.readText())
                .jsonObject
                .getValue("result")
                .jsonObject,
        )

    private val perp = recorded("order-book-perp.json")
    private val at = perp.timestampMs
    private var now = at
    private val asked = mutableListOf<String>()
    private var answer = perp
    private val file = "depth/BTC_USDC-PERPETUAL/2026-10-05.csv"

    private fun depth(dir: Path) = DeribitDepth({ code, _ -> answer.also { asked += code } }, dir, { now })

    private fun text(d: VenueDepth) =
        d.bids.map { it.price.toPlainString() } to d.asks.map { it.amount.toPlainString() }

    @Test
    fun `a recorded book is its ten best levels a side, exact as Deribit wrote them, at Deribit's stamp`() {
        val book = DeribitMarketMapping.depth(perp)

        assertThat(book.timeMs).isEqualTo(1_791_171_007_549L)
        assertThat(book.bids).hasSize(10)
        assertThat(book.asks).hasSize(10)
        assertThat(
            book.bids
                .first()
                .price
                .toPlainString(),
        ).isEqualTo("86472.7")
        assertThat(
            book.bids
                .first()
                .amount
                .toPlainString(),
        ).isEqualTo("10.0")
        assertThat(
            book.asks
                .first()
                .price
                .toPlainString(),
        ).isEqualTo("86472.8")
        assertThat(book.bids.map { it.price.toPlainString() }).contains("86200", "86000")
        assertThat(
            book.asks
                .last()
                .price
                .toPlainString(),
        ).isEqualTo("87000")
    }

    @Test
    fun `an option with no bids maps to an empty side, its offer kept`() {
        val book = DeribitMarketMapping.depth(recorded("order-book-option-no-bids.json"))

        assertThat(book.bids).isEmpty()
        assertThat(book.asks.map { it.price.toPlainString() to it.amount.toPlainString() })
            .containsExactly("10.0" to "100.0")
    }

    @Test
    fun `a read reaching the present records the book and serves it again after a restart`(
        @TempDir dir: Path,
    ) {
        val first = depth(dir).read("BTC_USDC-PERPETUAL", at - 60_000, at)
        now = at + 3_600_000
        val restarted = depth(dir).read("BTC_USDC-PERPETUAL", at - 60_000, at)

        assertThat(first.map { it.timeMs }).containsExactly(at)
        assertThat(restarted.map(::text)).isEqualTo(first.map(::text))
        assertThat(
            restarted
                .single()
                .bids
                .first()
                .amount
                .toPlainString(),
        ).isEqualTo("10.0")
        assertThat(asked).containsExactly("BTC_USDC-PERPETUAL")
        val lines = Files.readAllLines(dir.resolve(file))
        assertThat(lines.first()).isEqualTo("time,bids,asks")
        assertThat(lines[1]).startsWith("$at,86472.7:10.0 86466.0:0.5783 ").contains(",86472.8:10.0 ")
    }

    @Test
    fun `a book no newer than the last recorded is not recorded twice, even after a restart, a newer one is`(
        @TempDir dir: Path,
    ) {
        depth(dir).read("BTC_USDC-PERPETUAL", 0, at)
        val series = depth(dir)
        series.read("BTC_USDC-PERPETUAL", 0, at)
        answer = perp.copy(timestampMs = at + 10_000, bids = listOf(BigDecimal("86470") to BigDecimal("2")))
        now = at + 10_000

        val served = series.read("BTC_USDC-PERPETUAL", 0, now)

        assertThat(served.map { it.timeMs }).containsExactly(at, at + 10_000)
        assertThat(
            served
                .last()
                .bids
                .single()
                .price
                .toPlainString(),
        ).isEqualTo("86470")
    }

    @Test
    fun `a read of the past records nothing and serves only the days and times it asks for`(
        @TempDir dir: Path,
    ) {
        depth(dir).read("BTC_USDC-PERPETUAL", 0, at)
        now = at + 86_400_000
        asked.clear()

        assertThat(depth(dir).read("BTC_USDC-PERPETUAL", at + 1, at + 60_000)).isEmpty()
        assertThat(depth(dir).read("BTC_USDC-PERPETUAL", at - 1, at + 60_000).map { it.timeMs }).containsExactly(at)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a code that is not an instrument name is refused before anything is read or written`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { depth(dir).read("../BTC", 0, now) }.isInstanceOf(VenueRefusedException::class.java)
        assertThat(asked).isEmpty()
        assertThat(Files.exists(dir.resolve("depth"))).isFalse
    }

    @Test
    fun `a last line an append left without its newline is left out, cut off, and recording carries on`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, "time,bids,asks\n${at - 10_000},86470:1,86471:2\n${at - 1_000},8647")

        val served = depth(dir).read("BTC_USDC-PERPETUAL", 0, at)

        assertThat(served.map { it.timeMs }).containsExactly(at - 10_000, at)
        assertThat(Files.readAllLines(path).drop(1).map { it.substringBefore(',') })
            .containsExactly("${at - 10_000}", "$at")
    }

    @Test
    fun `a malformed complete line fails naming the file and line`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, "time,bids,asks\n${at - 10_000},86470:1x,86471:2\n")
        now = at + 86_400_000

        assertThatThrownBy { depth(dir).read("BTC_USDC-PERPETUAL", 0, at) }
            .hasMessageContaining("2026-10-05.csv line 2")
    }
}

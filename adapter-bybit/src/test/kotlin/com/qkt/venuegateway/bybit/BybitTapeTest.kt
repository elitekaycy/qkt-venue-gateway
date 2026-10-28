package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.recording.RecordedTape
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import com.qkt.venuegateway.bybit.client.BybitPrint
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitTapeTest {
    @TempDir lateinit var dir: Path

    private val recent =
        Json
            .parseToJsonElement(Fixtures.text("recent-trade-linear.json"))
            .jsonObject["result"]!!
            .jsonObject["list"]!!
            .jsonArray
            .map { BybitMarketJson.recentTrade(it.jsonObject) }
            .sortedBy { it.timeMs }
    private val liquidations =
        Json
            .parseToJsonElement(Fixtures.text("ws-linear-all-liquidation.json"))
            .jsonObject["data"]!!
            .jsonArray
            .map { BybitMarketJson.liquidation(it.jsonObject) }
    private val taped = mutableListOf<String>()

    /** Bybit's tape as the adapter records it: its pushes and latest trades mapped, then recorded. */
    private inner class Tape(
        now: Long,
        days: Int,
        private val latest: List<BybitPrint>,
    ) {
        private val recorded = RecordedTape(dir, { now }, days) { taped += it }

        fun trades(
            code: String,
            fromMs: Long,
            toMs: Long,
            limit: Int,
        ) = recorded.trades(code, fromMs, toMs, limit) { latest.map(BybitMarketMapping::print) }

        fun liquidations(
            code: String,
            fromMs: Long,
            toMs: Long,
        ) = recorded.liquidations(code, fromMs, toMs)

        fun onTrades(
            code: String,
            prints: List<BybitPrint>,
        ) = recorded.onTrades(code, prints.map(BybitMarketMapping::print))

        fun onLiquidations(
            code: String,
            prints: List<BybitPrint>,
        ) = recorded.onLiquidations(code, prints.map(BybitMarketMapping::liquidation))

        fun resume() = recorded.resume()

        fun prune() = recorded.prune()
    }

    private fun tape(now: Long = 1_791_241_500_000L) = Tape(now, 0, recent)

    @Test
    fun `a first read records bybit's latest trades and serves the range oldest first`() {
        val prints = tape().trades("BTCUSDT", 1_791_239_681_296, 1_791_239_691_514, 50)

        assertThat(
            prints.map {
                it.timeMs
            },
        ).containsExactly(1_791_239_681_296L, 1_791_239_681_296L, 1_791_239_686_404L, 1_791_239_686_404L)
        assertThat(prints.map { it.id }).doesNotHaveDuplicates()
        assertThat(prints.first().side).isEqualTo(Side.SELL)
        assertThat(taped).containsExactly("BTCUSDT")
    }

    @Test
    fun `a read takes the first prints up to its limit, and reading again records nothing twice`() {
        val tape = tape()
        tape.trades("BTCUSDT", 0, Long.MAX_VALUE, 2)
        val again = tape.trades("BTCUSDT", 0, Long.MAX_VALUE, 2)

        assertThat(again.map { it.timeMs }).containsExactly(1_791_239_681_296L, 1_791_239_681_296L)
        assertThat(lines("trades")).hasSize(5)
        assertThat(taped).containsExactly("BTCUSDT")
    }

    @Test
    fun `pushed trades are recorded, and one pushed and read back is served once`() {
        val tape = tape()
        val pushed = BybitPrint("pushed-1", 1_791_239_700_000, recent.last().price, recent.last().size, "Buy")
        tape.onTrades("BTCUSDT", listOf(recent.last(), pushed))

        val prints = tape.trades("BTCUSDT", 1_791_239_691_514, 1_791_239_700_001, 50)

        assertThat(prints.map { it.id }).containsExactly(recent.last().id, "pushed-1")
        assertThat(prints.last().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `liquidations heard are served with the side of the order that closed the position`() {
        val tape = tape()
        tape.onLiquidations("BTCUSDT", liquidations)

        val served = tape.liquidations("BTCUSDT", 1_791_241_484_423, 1_791_241_484_424)

        assertThat(served).hasSize(2)
        assertThat(served.map { it.side }).containsOnly(Side.BUY)
        assertThat(served.map { it.id }).doesNotHaveDuplicates()
        assertThat(tape.liquidations("BTCUSDT", 1_791_241_484_424, 1_791_241_490_000)).isEmpty()
    }

    @Test
    fun `codes taped before a restart are taped again`() {
        tape().liquidations("BTCUSDT", 0, 1)
        taped.clear()

        tape().resume()

        assertThat(taped).containsExactly("BTCUSDT")
    }

    @Test
    fun `a line cut short by a crash is never served and is cut off before the next append`() {
        val tape = tape()
        tape.onTrades("BTCUSDT", listOf(recent.first()))
        val file = Files.list(dir.resolve("trades/BTCUSDT")).use { it.toList().single() }
        Files.writeString(file, "1791239681296,torn", java.nio.file.StandardOpenOption.APPEND)

        assertThat(tape.liquidations("BTCUSDT", 0, 1)).isEmpty()
        assertThat(tape().trades("BTCUSDT", 0, 1_791_239_681_297, 50).map { it.id }).contains(recent.first().id)
        assertThat(Files.readString(file)).doesNotContain("torn")
    }

    @Test
    fun `day files past the retention are pruned, at most once a day`() {
        val old = BybitPrint("old", 1_790_000_000_000, recent.first().price, recent.first().size, "Buy")
        val tape = Tape(1_791_241_500_000L, 3, emptyList())
        tape.onTrades("BTCUSDT", listOf(old, recent.first()))

        assertThat(tape.prune()).isEqualTo(1)
        assertThat(tape.prune()).isZero
        assertThat(tape.trades("BTCUSDT", 0, Long.MAX_VALUE, 50).map { it.id }).containsExactly(recent.first().id)
    }

    @Test
    fun `recorded lines keep bybit's words, a liquidation's side the position liquidated`() {
        val tape = tape()
        tape.onLiquidations("BTCUSDT", liquidations.take(1))
        tape.onTrades("BTCUSDT", listOf(recent.first()))

        val p = liquidations.first()
        val t = recent.first()
        assertThat(lines("liquidations"))
            .containsExactly("${p.timeMs},${p.id},${p.price.toPlainString()},${p.size.toPlainString()},${p.side}")
        assertThat(lines("trades"))
            .containsExactly("${t.timeMs},${t.id},${t.price.toPlainString()},${t.size.toPlainString()},${t.side}")
    }

    private fun lines(kind: String) =
        Files
            .walk(dir.resolve(kind))
            .use { paths ->
                paths.filter(Files::isRegularFile).toList()
            }.flatMap { Files.readAllLines(it) }
}

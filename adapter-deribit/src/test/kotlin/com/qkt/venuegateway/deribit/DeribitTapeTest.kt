package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitJson
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitPublicTrade
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The tape and liquidations from pages recorded on mainnet's history host (2026-10-05), asked 20 at a time:
 * `tape-liquidation-taker.json` holds BTC_USDC-PERPETUAL's prints from 1791096880272 ms, cut by `has_more` after
 * one of the eleven prints of 1791096884055 ms, whose rest open `tape-liquidation-taker-next.json`; a short was
 * liquidated by a taker buy at 1791096882272 (`T`). `tape-liquidation-maker.json` holds UNI_USDC-PERPETUAL's
 * print at 1791148295840 that liquidated its maker (`M`), a long whose resting sell a taker bought.
 */
class DeribitTapeTest {
    private val from = 1_791_096_880_272L
    private val to = 1_791_096_884_273L
    private val cut = 1_791_096_884_055L
    private val btc = "BTC_USDC-PERPETUAL"

    private fun page(name: String): DeribitPage<DeribitPublicTrade> {
        val result =
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/$name")!!.readText())
                .jsonObject["result"]!!
                .jsonObject
        return DeribitPage(DeribitJson.publicTrades(result), result["has_more"]!!.jsonPrimitive.boolean)
    }

    private val taker =
        Recorded(mapOf(from to page("tape-liquidation-taker.json"), cut to page("tape-liquidation-taker-next.json")))

    @Test
    fun `prints carry deribit's trade id, price, exact amount and the taker's side, oldest first`() {
        val prints = DeribitTape.prints(taker, btc, from, to, limit = 20, perCall = 20)

        assertThat(taker.asked).containsExactly(from to to - 1)
        assertThat(prints).hasSize(20)
        assertThat(prints.first().id).isEqualTo("USDC-65965319")
        assertThat(prints.first().size.toPlainString()).isEqualTo("0.0002")
        assertThat(prints.first().price.toPlainString()).isEqualTo("85072.7")
        assertThat(prints.map { it.side }.distinct()).containsExactly(Side.BUY, Side.SELL)
        assertThat(prints.filter { it.timeMs == 1_791_096_880_792L }.map { it.id })
            .describedAs("one millisecond's prints, recorded out of sequence, in sequence order")
            .containsExactly(
                "USDC-65965320",
                "USDC-65965321",
                "USDC-65965322",
                "USDC-65965323",
                "USDC-65965324",
                "USDC-65965325",
            )
        assertThat(prints.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }).isTrue
    }

    @Test
    fun `the whole tape across a cut millisecond holds each print once, the next page starting in it`() {
        val prints = DeribitTape.prints(taker, btc, from, to, limit = 100, perCall = 20)

        assertThat(taker.asked.map { it.first }).containsExactly(from, cut)
        assertThat(prints).hasSize(34)
        assertThat(prints.map { it.id }).doesNotHaveDuplicates()
        assertThat(prints.count { it.timeMs == cut }).isEqualTo(11)
    }

    @Test
    fun `a taker liquidation is the print on its own side, found by reading every page`() {
        val liquidations = DeribitTape.liquidations(taker, btc, from, to, perCall = 20)

        assertThat(taker.asked.map { it.first }).containsExactly(from, cut)
        val print = liquidations.single()
        assertThat(print.id).isEqualTo("USDC-65965358")
        assertThat(print.timeMs).isEqualTo(1_791_096_882_272L)
        assertThat(print.side).describedAs("a short liquidated by buying").isEqualTo(Side.BUY)
        assertThat(print.size.toPlainString()).isEqualTo("0.0011")
        assertThat(print.price.toPlainString()).isEqualTo("85070.2")
    }

    @Test
    fun `a maker liquidation is on the side opposite the taker`() {
        val start = 1_791_148_293_840L
        val uni = Recorded(mapOf(start to page("tape-liquidation-maker.json")))

        val print = DeribitTape.liquidations(uni, "UNI_USDC-PERPETUAL", start, start + 4_001, perCall = 20).single()

        assertThat(print.id).isEqualTo("USDC-66106281")
        assertThat(print.side).describedAs("a long liquidated by selling to a taker buy").isEqualTo(Side.SELL)
        assertThat(print.size.toPlainString()).isEqualTo("60.74")
    }

    @Test
    fun `a print that liquidated both sides is one liquidation of each, and an unknown mark is refused`() {
        val recorded = page("tape-liquidation-taker.json").items.first { it.liquidation == "T" }

        // `MT` is Deribit's documented mark for both sides; none was seen in six days of USDC futures history.
        assertThat(DeribitTape.liquidatedSides(recorded.copy(liquidation = "MT"))).containsExactly(Side.BUY, Side.SELL)
        assertThat(DeribitTape.liquidatedSides(recorded.copy(liquidation = null))).isEmpty()
        assertThatThrownBy { DeribitTape.liquidatedSides(recorded.copy(liquidation = "X")) }
            .hasMessageContaining("unknown liquidation mark X")
    }

    @Test
    fun `a page that cannot advance past its millisecond fails rather than loop`() {
        val stuck =
            Recorded(
                mapOf(
                    from to page("tape-liquidation-taker.json"),
                    cut to page("tape-liquidation-taker.json"),
                ),
            )

        assertThatThrownBy { DeribitTape.liquidations(stuck, btc, from, to, perCall = 20) }
            .hasMessageContaining("cannot be paged")
    }

    @Test
    fun `the adapter reads the tape and liquidations from the history host`(
        @TempDir dir: Path,
    ) {
        val open = DeribitPrivateJson.order(recordedOrder())
        val (adapter, _) = ScriptedDeribit(open).adapter(dir, unusedMarket(), history = taker)

        assertThat(adapter.trades(btc, from, to, 1_000).map { it.id }).hasSize(34)
        assertThat(adapter.liquidations(btc, from, to).map { it.id }).containsExactly("USDC-65965358")
        adapter.close()
    }

    private fun recordedOrder() =
        Json
            .parseToJsonElement(javaClass.getResource("/fixtures/private/buy-limit-open.json")!!.readText())
            .jsonObject["response"]!!
            .jsonObject["result"]!!
            .jsonObject["order"]!!
            .jsonObject

    /** Answers each page by its start with the page Deribit answered for it, as recorded. */
    private class Recorded(
        val pages: Map<Long, DeribitPage<DeribitPublicTrade>>,
    ) : DeribitMarketData {
        val asked = mutableListOf<Pair<Long, Long>>()

        override fun tape(
            name: String,
            fromMs: Long,
            toMs: Long,
            count: Int,
        ): DeribitPage<DeribitPublicTrade> {
            asked += fromMs to toMs
            return pages[fromMs] ?: error("no page recorded from $fromMs")
        }

        override fun instruments(
            currency: String,
            kind: String,
        ): List<DeribitInstrument> = error("not read")

        override fun instrument(name: String): DeribitInstrument = error("not read")

        override fun ticker(name: String) = error("not read")

        override fun klines(
            name: String,
            minutes: Long,
            fromMs: Long,
            toMs: Long,
        ): List<DeribitKline> = error("not read")

        override fun deliveryPrices(
            index: String,
            count: Int,
        ): List<Pair<LocalDate, BigDecimal>> = error("not read")
    }
}

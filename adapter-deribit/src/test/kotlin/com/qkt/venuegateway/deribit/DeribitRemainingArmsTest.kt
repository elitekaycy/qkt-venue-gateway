package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.adapter.OrderChange
import com.qkt.venuegateway.deribit.client.DeribitEdits.num
import com.qkt.venuegateway.deribit.client.DeribitHistories
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitOrder
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import com.qkt.venuegateway.deribit.client.DeribitPublicTrade
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.klineResolution
import com.qkt.venuegateway.deribit.client.recordedObject
import com.qkt.venuegateway.deribit.client.recordedResult
import com.qkt.venuegateway.deribit.client.set
import com.qkt.venuegateway.deribit.market.DeribitTape
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeribitRemainingArmsTest {
    @Test
    fun `a day's klines go by Deribit's name for a day`() {
        assertThat(klineResolution(1_440)).isEqualTo("1D")
        assertThat(klineResolution(60)).isEqualTo("60")
    }

    @Test
    fun `the transaction log follows Deribit's continuation to the last page`() {
        val log = recordedObject("private/transaction-log-funding.json")
        val asked = mutableListOf<JsonObject>()
        val histories =
            DeribitHistories { _, params ->
                asked += params
                if (params["continuation"] == null) log.set("continuation" to num("149687000")) else log
            }

        val rows = histories.transactions("USDC", 0, 10)

        assertThat(asked).hasSize(2)
        assertThat(asked[1]["continuation"].toString()).isEqualTo("149687000")
        assertThat(rows.map { it.id }).doesNotHaveDuplicates().isNotEmpty
    }

    @Test
    fun `settlements outside the range asked are dropped from what Deribit answers`() {
        val page = recordedObject("private/settlement-history.json")
        val histories = DeribitHistories { _, _ -> page }

        val later = histories.settlements("USDC", 1_790_928_001_113L, Long.MAX_VALUE)
        val earlier = histories.settlements("USDC", 0, 1_791_014_400_570L)

        assertThat(later.map { it.timestampMs }.distinct()).containsExactly(1_791_014_400_571L)
        assertThat(earlier.map { it.timestampMs }.distinct()).containsExactly(1_790_928_001_112L)
    }

    private fun print(time: Long) =
        DeribitPublicTrade("t-$time", time, time, BigDecimal.ONE, BigDecimal.ONE, "buy", null)

    @Test
    fun `prints a venue sends before the range or at its end are not served`() {
        val history =
            object : DeribitMarketData {
                override fun instruments(
                    currency: String,
                    kind: String,
                ): List<DeribitInstrument> = error("unused")

                override fun instrument(name: String): DeribitInstrument = error("unused")

                override fun ticker(name: String): DeribitTicker = error("unused")

                override fun klines(
                    name: String,
                    minutes: Long,
                    fromMs: Long,
                    toMs: Long,
                ): List<DeribitKline> = error("unused")

                override fun deliveryPrices(
                    index: String,
                    count: Int,
                ): List<Pair<LocalDate, BigDecimal>> = error("unused")

                override fun tape(
                    name: String,
                    fromMs: Long,
                    toMs: Long,
                    count: Int,
                ) = DeribitPage(listOf(print(5), print(10), print(15), print(20)), false)
            }

        val prints = DeribitTape.prints(history, "X", 10, 20, 100)

        assertThat(prints.map { it.timeMs }).containsExactly(10L, 15L)
    }

    @Test
    fun `a change naming a quantity sends that quantity`(
        @TempDir dir: Path,
    ) {
        val open: DeribitOrder =
            DeribitPrivateJson.order(recordedResult("private/buy-limit-open.json").jsonObject["order"]!!.jsonObject)
        val deribit = ScriptedDeribit(open)
        deribit.byLabel[open.label!!] = listOf(open)
        val (adapter, _) = deribit.adapter(dir, unusedMarket())

        assertThat(adapter.modify(open.label!!, OrderChange(quantity = BigDecimal("0.0007")))).isTrue

        assertThat(deribit.edits.single()).contains(" 0.0007 ")
    }
}

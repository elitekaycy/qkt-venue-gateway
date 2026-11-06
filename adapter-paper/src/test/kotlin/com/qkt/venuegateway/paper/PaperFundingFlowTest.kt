package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.deribit.client.DeribitFundingRate
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperFundingFlowTest {
    private val hour = 3_600_000L
    private val perp = "BTC_USDC-PERPETUAL"
    private var now = 1_000 * hour + 1_800_000
    private val heard = CopyOnWriteArrayList<String>()
    private val published = CopyOnWriteArrayList<DeribitFundingRate>()

    private val listed =
        DeribitInstrument(
            perp,
            "future",
            true,
            null,
            null,
            null,
            BigDecimal.ONE,
            BigDecimal("0.5"),
            BigDecimal("0.01"),
            "USDC",
            "btc_usdc",
        )

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = if (kind == "future") listOf(listed) else emptyList()

            override fun instrument(name: String) = listed

            override fun ticker(name: String) =
                DeribitTicker(
                    name,
                    now,
                    BigDecimal("84000"),
                    BigDecimal.TEN,
                    BigDecimal("84001"),
                    BigDecimal.TEN,
                    null,
                    null,
                    null,
                    null,
                )

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ) = emptyList<DeribitKline>()

            override fun fundingRates(
                name: String,
                fromMs: Long,
                toMs: Long,
            ) = published.filter { it.timestampMs in fromMs..toMs }

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; heard $heard" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a perpetual held across an hour's published rate is charged it, pushed, and served for its range only`(
        @TempDir dir: Path,
    ) {
        val adapter =
            PaperAdapter(
                AdapterContext(mapOf("settlement_check_ms" to "50"), { now }, dir),
                market,
            ) { _, _ ->
                object : DeribitTickers {
                    override fun start() {}

                    override fun subscribe(names: Set<String>) {}

                    override fun close() {}
                }
            }
        adapter.connect(HeardListener(heard))
        adapter.place(NewOrder("p-1", perp, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.GTC))
        Thread.sleep(400)
        published += DeribitFundingRate(1_001 * hour, BigDecimal("0.0001"), BigDecimal("84000"))
        now = 1_001 * hour + 600_000

        await { heard.any { it.startsWith("funding $perp") } }
        val at = adapter.funding(0, Long.MAX_VALUE).single().timeMs

        assertThat(adapter.funding(at, at)).hasSize(1)
        assertThat(adapter.funding(0, at - 1)).isEmpty()
        assertThat(adapter.funding(at + 1, Long.MAX_VALUE)).isEmpty()
        assertThat(adapter.funding(0, Long.MAX_VALUE).single().amount).isEqualByComparingTo("8.4")
        adapter.close()
    }
}

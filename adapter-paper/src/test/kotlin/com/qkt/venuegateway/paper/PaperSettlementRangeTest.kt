package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
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

class PaperSettlementRangeTest {
    private val put = "BTC_USDC-2OCT26-82000-P"
    private val expiry = 1_790_928_000_000L
    private var now = expiry - 3_600_000
    private val heard = CopyOnWriteArrayList<String>()

    private val listed =
        DeribitInstrument(
            put,
            "option",
            false,
            expiry,
            BigDecimal("82000"),
            "put",
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
            ) = if (kind == "option") listOf(listed) else emptyList()

            override fun instrument(name: String) = listed

            override fun ticker(name: String) =
                DeribitTicker(
                    name,
                    now,
                    BigDecimal("900"),
                    BigDecimal.TEN,
                    BigDecimal("1000"),
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

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = listOf(LocalDate.parse("2026-10-02") to BigDecimal("81000"))
        }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; heard $heard" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a settlement is served for the range it falls in and for no other`(
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
        adapter.place(NewOrder("p-1", put, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.GTC))
        now = expiry + 60_000
        await { heard.any { it.startsWith("settle") } }
        val at = adapter.settlements(0, Long.MAX_VALUE).single().timeMs

        assertThat(adapter.settlements(at, at)).hasSize(1)
        assertThat(adapter.settlements(0, at - 1)).isEmpty()
        assertThat(adapter.settlements(at + 1, Long.MAX_VALUE)).isEmpty()
        adapter.close()
    }
}

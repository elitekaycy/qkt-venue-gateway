package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitPublicTrade
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The paper venue's tape and liquidations are Deribit's, read from its trade history; the prints are three of
 * BTC_USDC-PERPETUAL's as recorded on mainnet's history host (2026-10-05), the second liquidating a short.
 */
class PaperTapeTest {
    private val from = 1_791_096_880_272L
    private val to = 1_791_096_884_273L
    private val tape =
        listOf(
            DeribitPublicTrade(
                "USDC-65965319",
                14_075_400L,
                1_791_096_880_791L,
                BigDecimal("85072.7"),
                BigDecimal("0.0002"),
                "buy",
                null,
            ),
            DeribitPublicTrade(
                "USDC-65965358",
                14_075_450L,
                1_791_096_882_272L,
                BigDecimal("85070.2"),
                BigDecimal("0.0011"),
                "buy",
                "T",
            ),
            DeribitPublicTrade(
                "USDC-65965360",
                14_075_451L,
                1_791_096_882_777L,
                BigDecimal("85070.1"),
                BigDecimal("0.0016"),
                "sell",
                null,
            ),
        )

    /** The trade history the adapter is handed; every other market call fails, so none is made. */
    private val history =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DeribitMarketData::class.java)) { _, method, args ->
            when (method.name) {
                "tape" -> DeribitPage(tape.filter { it.timestampMs in args[1] as Long..args[2] as Long }, false)
                else -> error("market ${method.name} is not used here")
            }
        } as DeribitMarketData

    private val unused =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DeribitMarketData::class.java)) { _, method, _ ->
            error("live market ${method.name} is not used here")
        } as DeribitMarketData

    @Test
    fun `it declares the tape and liquidations and serves both from its history, not its live market`(
        @TempDir dir: Path,
    ) {
        val adapter = PaperAdapter(AdapterContext(emptyMap(), { to }, dir), unused, history) { _, _ -> Idle }

        val prints = adapter.trades("BTC_USDC-PERPETUAL", from, to, 1_000)
        val liquidations = adapter.liquidations("BTC_USDC-PERPETUAL", from, to)

        assertThat(adapter.capabilities).contains(Capability.TRADES, Capability.LIQUIDATIONS)
        assertThat(prints.map { it.id }).containsExactly("USDC-65965319", "USDC-65965358", "USDC-65965360")
        assertThat(prints.map { it.side }).containsExactly(Side.BUY, Side.BUY, Side.SELL)
        assertThat(liquidations.single().id).isEqualTo("USDC-65965358")
        assertThat(liquidations.single().side).describedAs("a short liquidated by buying").isEqualTo(Side.BUY)
        assertThat(liquidations.single().size.toPlainString()).isEqualTo("0.0011")
        adapter.close()
    }

    private object Idle : DeribitTickers {
        override fun start() {}

        override fun subscribe(names: Set<String>) {}

        override fun close() {}
    }
}

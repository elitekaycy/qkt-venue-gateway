package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.deribit.client.DeribitMarkTrade
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitPage
import com.qkt.venuegateway.deribit.client.DeribitTickers
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The paper venue's marks are Deribit's, read from its trade history; values as recorded on testnet 2026-10-04. */
class PaperMarksTest {
    private val minute = 60_000L
    private val from = 1_791_154_800_000L
    private val tape =
        listOf(
            DeribitMarkTrade(14_316_645L, 1_791_154_801_271L, BigDecimal("86462.46"), BigDecimal("86429.73")),
            DeribitMarkTrade(14_316_719L, 1_791_154_859_833L, BigDecimal("86482.3"), BigDecimal("86451.59")),
            DeribitMarkTrade(14_316_825L, 1_791_154_972_540L, BigDecimal("86412.98"), BigDecimal("86386.86")),
        )

    /** The trade history the adapter is handed; every other market call fails, so none is made. */
    private val history =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DeribitMarketData::class.java)) {
            _,
            method,
            args,
            ->
            when (method.name) {
                "edgeTrade" -> {
                    val inRange = tape.filter { it.timestampMs in args[1] as Long..args[2] as Long }
                    if (args[3] as Boolean) inRange.lastOrNull() else inRange.firstOrNull()
                }
                "tradesFrom" -> DeribitPage(tape.filter { it.timestampMs in args[1] as Long..args[2] as Long }, false)
                else -> error("market ${method.name} is not used here")
            }
        } as DeribitMarketData

    @Test
    fun `it declares mark prices and serves each window's last trade from its history, not its live market`(
        @TempDir dir: Path,
    ) {
        val unused =
            java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DeribitMarketData::class.java)) {
                _,
                method,
                _,
                ->
                error("live market ${method.name} is not used here")
            } as DeribitMarketData
        val adapter =
            PaperAdapter(AdapterContext(emptyMap(), { from + 3 * minute }, dir), unused, history) { _, _ -> Idle }

        val marks = adapter.marks("BTC_USDC-PERPETUAL", minute, from, from + 3 * minute)

        assertThat(adapter.capabilities).contains(Capability.MARK_PRICES)
        assertThat(marks.map { it.timeMs }).containsExactly(1_791_154_859_833L, 1_791_154_972_540L)
        assertThat(marks.map { it.mark!!.toPlainString() }).containsExactly("86482.3", "86412.98")
        assertThat(marks.map { it.index!!.toPlainString() }).containsExactly("86451.59", "86386.86")
        adapter.close()
    }

    private object Idle : DeribitTickers {
        override fun start() {}

        override fun subscribe(names: Set<String>) {}

        override fun close() {}
    }
}

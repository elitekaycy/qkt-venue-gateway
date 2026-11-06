package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.deribit.client.DeribitInstrument
import com.qkt.venuegateway.deribit.client.DeribitKline
import com.qkt.venuegateway.deribit.client.DeribitMarketData
import com.qkt.venuegateway.deribit.client.DeribitTicker
import com.qkt.venuegateway.deribit.market.DeribitListing
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperSettlementTest {
    private val expiry = 1_790_928_000_000L
    private val now = expiry + 60_000
    private val call = "BTC_USDC-2OCT26-80000-C"
    private val put = "BTC_USDC-2OCT26-80000-P"
    private val future = "BTC_USDC-2OCT26"
    private val later = "BTC_USDC-9OCT26-80000-C"
    private val eth = "ETH_USDC-2OCT26"
    private val perp = "BTC_USDC-PERPETUAL"

    private val listed =
        listOf(
            listed(call, expiry, "80000", "call"),
            listed(put, expiry, "80000", "put"),
            listed(future, expiry, null, null),
            listed(later, expiry + 7 * 86_400_000, "80000", "call"),
            listed(eth, expiry, null, null, "eth_usdc"),
            listed(perp, null, null, null),
        )

    private val market =
        object : DeribitMarketData {
            override fun instruments(
                currency: String,
                kind: String,
            ) = listed.filter { it.kind == kind }

            override fun instrument(name: String) = listed.first { it.name == name }

            override fun ticker(name: String): DeribitTicker = error("unused")

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ) = emptyList<DeribitKline>()

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = if (index == "btc_usdc") listOf(LocalDate.parse("2026-10-02") to BigDecimal("81000")) else emptyList()
        }

    private val listing = DeribitListing(market, "USDC", { now })

    private fun listed(
        name: String,
        expiryMs: Long?,
        strike: String?,
        optionType: String?,
        index: String = "btc_usdc",
    ) = DeribitInstrument(
        name,
        if (strike == null) "future" else "option",
        expiryMs == null,
        expiryMs,
        strike?.let(::BigDecimal),
        optionType,
        BigDecimal.ONE,
        BigDecimal("0.5"),
        BigDecimal("0.01"),
        "USDC",
        index,
    )

    private fun book(vararg held: String) =
        PaperBook(PaperLedger(BigDecimal("10000")), "USDC", BigDecimal.ZERO).apply {
            held.forEach { ledger.apply(it, Side.BUY, BigDecimal.ONE, BigDecimal("500")) }
        }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `expired contracts settle at intrinsic value or the delivery price, the rest wait`() {
        val book = book(call, put, future, later, eth, perp)

        val settled = PaperSettlement(market, listing, { now }).use { it.due(book) }

        assertThat(settled.map { it.symbol to it.price.toPlainString() }).containsExactly(
            call to "1000",
            put to "0",
            future to "81000",
        )
        assertThat(settled.map { it.timeMs }).containsOnly(expiry)
        assertThat(book.ledger.positions.keys).containsExactly(later, eth, perp)
        assertThat(book.ledger.balance).isEqualByComparingTo("90500")
    }

    @Test
    fun `a failing check is logged and the timer keeps checking`() {
        val calls = AtomicInteger()
        val settlement = PaperSettlement(market, listing, { now }, periodMs = 20)

        settlement.start { if (calls.incrementAndGet() == 1) error("deribit down") }

        await { calls.get() >= 2 }
        settlement.close()
    }

    @Test
    fun `an upkeep run whose prune fails still settles and saves, delivering to the listener of the moment`(
        @TempDir dir: Path,
    ) {
        val book = book(future)
        val store = PaperStore(dir)
        val prunes = AtomicInteger()
        val heard = CopyOnWriteArrayList<String>()
        var listener: HeardListener? = null
        val upkeep =
            PaperUpkeep(
                book,
                store,
                PaperSettlement(market, listing, { now }, periodMs = 20),
                PaperFunding(market, listing, "USDC") { now },
                { prunes.incrementAndGet().also { error("disk full") } },
                { it.run() },
            ) { listener }

        upkeep.start()
        await { synchronized(book) { book.state.settlements.isNotEmpty() } }
        val saved = book().also { store.load(it) }
        assertThat(saved.state.settlements.map { it.symbol }).containsExactly(future)
        assertThat(heard).isEmpty()

        listener = HeardListener(heard)
        synchronized(book) { book.ledger.apply(call, Side.BUY, BigDecimal.ONE, BigDecimal("500")) }
        await { heard.isNotEmpty() }
        upkeep.close()

        assertThat(heard).containsExactly("settle $call 1000")
        assertThat(prunes.get()).isGreaterThanOrEqualTo(2)
    }
}

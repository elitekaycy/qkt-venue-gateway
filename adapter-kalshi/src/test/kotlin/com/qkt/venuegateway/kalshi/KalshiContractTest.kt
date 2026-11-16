package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.Credentials
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueAdapter
import com.qkt.venuegateway.kalshi.client.KalshiMarket
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiRest
import com.qkt.venuegateway.support.JsonReads.need
import com.qkt.venuegateway.support.JsonReads.objects
import com.qkt.venuegateway.testkit.AdapterContractTest
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The Kalshi adapter against the demo environment: real orders on the demo market trading most right now that is
 * quoted on both sides away from the edges of a whole-cent grid ([Demo]), so an order fills, closes and rests. The
 * gateway lists that market's series alone. Runs only with `KALSHI_CLIENT_ID` (the API key id) and
 * `KALSHI_CLIENT_SECRET` (its PEM private key) set to a demo key on a funded demo account. No test may run past five
 * minutes: a venue that stalls fails the suite, it never holds the build.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KalshiContractTest : AdapterContractTest() {
    private val id = System.getenv("KALSHI_CLIENT_ID")
    private val secret = System.getenv("KALSHI_CLIENT_SECRET")
    private val one = BigDecimal.ONE

    @BeforeEach
    fun demoKey() = assumeTrue(!id.isNullOrBlank() && !secret.isNullOrBlank(), "no demo key")

    override fun newAdapter(stateDir: Path): VenueAdapter =
        KalshiAdapterFactory().create(
            AdapterContext(
                mapOf("environment" to "demo", "series" to Demo.series),
                System::currentTimeMillis,
                stateDir,
                Credentials(id, secret),
            ),
        )

    /** A buy two cents from the floor, under the bid: it rests, and has a cent left to move down. */
    override fun restingOrder(clientOrderId: String) = limit(clientOrderId, "0.02")

    /** A market buy from the resting ask. */
    override fun fillingOrder(clientOrderId: String) = market(clientOrderId, Side.BUY, reduceOnly = false)

    /** A market sell into the resting bid. */
    override fun closingOrder(clientOrderId: String) = market(clientOrderId, Side.SELL, reduceOnly = true)

    /** A price between cents on a whole-cent market: Kalshi refuses it (`400 invalid_price`). */
    override fun refusedOrder(clientOrderId: String) = limit(clientOrderId, "0.015")

    override val activeCode: String get() = Demo.market.ticker

    /** qkt ends each of its order ids `.<suffix>`, and Kalshi refuses a `.` in an id: the label must still work. */
    @Test
    fun `a label with a dot rests, is found by it and is cancelled`(
        @TempDir dir: Path,
    ) {
        val label = "kit.dot-${System.currentTimeMillis()}"
        newAdapter(dir).use { adapter ->
            val placed = adapter.place(restingOrder(label))
            assertThat(placed.clientOrderId).isEqualTo(label)
            assertThat(adapter.orderByLabel(label)?.status).isEqualTo(OrderStatus.WORKING)
            adapter.cancel(label)
            assertThat(adapter.orderByLabel(label)?.status).isEqualTo(OrderStatus.CANCELLED)
        }
    }

    private fun market(
        clientOrderId: String,
        side: Side,
        reduceOnly: Boolean,
    ) = NewOrder(clientOrderId, activeCode, side, OrderType.MARKET, one, null, null, TimeInForce.GTC, reduceOnly)

    private fun limit(
        clientOrderId: String,
        price: String,
    ) = NewOrder(clientOrderId, activeCode, Side.BUY, OrderType.LIMIT, one, BigDecimal(price), null, TimeInForce.GTC)

    /**
     * The market the suite trades, chosen once a run from Kalshi's latest public trades (the demo's makers and takers
     * are bots, and which events they work changes by the week): the most traded shard-0 market of a single
     * whole-cent grid whose bid and ask both hold [DEPTH] contracts between [LOW] and [HIGH].
     */
    private object Demo {
        private val LOW = BigDecimal("0.05")
        private val HIGH = BigDecimal("0.95")
        private val DEPTH = BigDecimal.TEN
        private val CENT = BigDecimal("0.01")
        private const val LATEST = "1000"
        private const val CANDIDATES = 100
        private val rest = KalshiRest(KalshiEnvironment.DEMO.restUrl)
        private val public = KalshiPublic(rest)

        val market: KalshiMarket by lazy {
            val trades = rest.get("/markets/trades", listOf("limit" to LATEST)).objects("trades")
            val counts = trades.groupingBy { it.need("ticker") }.eachCount()
            val busiest = counts.entries.sortedByDescending { it.value }.map { it.key }
            val markets = public.markets(busiest.take(CANDIDATES)).associateBy { it.ticker }
            busiest.mapNotNull(markets::get).firstOrNull(::tradable)
                ?: error("the demo trades no two-sided whole-cent market now: ${busiest.take(5)}")
        }

        val series: String by lazy { public.event(market.eventTicker)!!.seriesTicker }

        private fun tradable(m: KalshiMarket): Boolean {
            val (bid, ask) = (m.yesBid ?: return false) to (m.yesAsk ?: return false)
            val deep = listOf(m.yesBidSize, m.yesAskSize).all { it != null && it >= DEPTH }
            val step = m.priceRanges.singleOrNull()?.step
            val whole = step?.compareTo(CENT) == 0
            return m.status == "active" && (m.exchangeIndex ?: 0) == 0 && whole && deep && bid >= LOW && ask <= HIGH
        }
    }
}

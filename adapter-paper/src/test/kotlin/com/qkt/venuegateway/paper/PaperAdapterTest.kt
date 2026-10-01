package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.AdapterListener
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderStatus
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueFill
import com.qkt.venuegateway.adapter.VenueOrder
import com.qkt.venuegateway.adapter.VenueQuote
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.adapter.VenueSettlement
import com.qkt.venuegateway.deribit.DeribitInstrument
import com.qkt.venuegateway.deribit.DeribitKline
import com.qkt.venuegateway.deribit.DeribitMarketData
import com.qkt.venuegateway.deribit.DeribitTicker
import com.qkt.venuegateway.deribit.DeribitTickers
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperAdapterTest {
    private val perp = "BTC_USDC-PERPETUAL"
    private val put = "BTC_USDC-2OCT26-82000-P"
    private val expiry = 1_790_928_000_000L
    private var now = expiry - 3_600_000

    private val market =
        object : DeribitMarketData {
            val tickers = mutableMapOf<String, DeribitTicker>()

            override fun instruments(
                currency: String,
                kind: String,
            ) = if (kind ==
                "future"
            ) {
                listOf(instrument(perp, "future", null, null))
            } else {
                listOf(instrument(put, "option", expiry, "82000"))
            }

            override fun instrument(name: String) =
                instruments("USDC", "future").plus(instruments("USDC", "option")).first {
                    it.name ==
                        name
                }

            override fun ticker(name: String) = tickers.getValue(name)

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

    private val subscribed = CopyOnWriteArrayList<Set<String>>()
    private val heard = CopyOnWriteArrayList<String>()
    private var onTicker: (DeribitTicker) -> Unit = {}

    private fun instrument(
        name: String,
        kind: String,
        expiryMs: Long?,
        strike: String?,
    ) = DeribitInstrument(
        name,
        kind,
        expiryMs == null,
        expiryMs,
        strike?.let(::BigDecimal),
        strike?.let {
            "put"
        },
        BigDecimal.ONE,
        BigDecimal("0.5"),
        BigDecimal("0.01"),
        "USDC",
        "btc_usdc",
    )

    private fun ticker(
        name: String,
        bid: String?,
        ask: String?,
    ) = DeribitTicker(
        name,
        now,
        bid?.let(::BigDecimal),
        bid?.let {
            BigDecimal.TEN
        },
        ask?.let(::BigDecimal),
        ask?.let { BigDecimal.TEN },
        null,
        null,
        null,
        null,
    )

    private fun adapter(dir: Path): PaperAdapter {
        val adapter =
            PaperAdapter(
                AdapterContext(mapOf("starting_balance" to "10000", "settlement_check_ms" to "50"), {
                    now
                }, dir),
                market,
            ) { t, _ ->
                onTicker = t
                object : DeribitTickers {
                    override fun start() {}

                    override fun subscribe(names: Set<String>) {
                        subscribed += names
                    }

                    override fun close() {}
                }
            }
        adapter.connect(
            object : AdapterListener {
                override fun order(order: VenueOrder) {
                    heard += "order ${order.clientOrderId} ${order.status}"
                }

                override fun fill(fill: VenueFill) {
                    heard += "fill ${fill.clientOrderId} ${fill.price.toPlainString()}"
                }

                override fun settlement(settlement: VenueSettlement) {
                    heard += "settle ${settlement.symbol} ${settlement.price.toPlainString()}"
                }

                override fun quote(quote: VenueQuote) {}

                override fun connection(
                    up: Boolean,
                    reason: String,
                ) {}
            },
        )
        return adapter
    }

    private fun order(
        id: String,
        symbol: String = perp,
        type: OrderType = OrderType.MARKET,
        limit: String? = null,
    ) = NewOrder(id, symbol, Side.BUY, type, BigDecimal.ONE, limit?.let(::BigDecimal), null, TimeInForce.GTC)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out; heard $heard" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a market order fills at the ask and is reported order first, then fill, and survives a restart`(
        @TempDir dir: Path,
    ) {
        market.tickers[perp] = ticker(perp, "84000", "84000.5")
        val adapter = adapter(dir)

        val placed = adapter.place(order("a-1"))

        assertThat(placed.status).isEqualTo(OrderStatus.FILLED)
        await { heard.size >= 2 }
        assertThat(heard).containsExactly("order a-1 FILLED", "fill a-1 84000.5")
        adapter.close()
        val restarted = adapter(dir)
        assertThat(
            restarted
                .positions()
                .rows
                .single()
                .quantity,
        ).isEqualByComparingTo("1")
        assertThat(restarted.orderByLabel("a-1")?.status).isEqualTo(OrderStatus.FILLED)
        restarted.close()
    }

    @Test
    fun `a resting limit is working, fills on a later ticker, and an order with no side quoted is refused`(
        @TempDir dir: Path,
    ) {
        market.tickers[perp] = ticker(perp, "84000", "84000.5")
        val adapter = adapter(dir)

        assertThat(
            adapter.place(order("l-1", type = OrderType.LIMIT, limit = "83990")).status,
        ).isEqualTo(OrderStatus.WORKING)
        assertThat(subscribed.last()).contains(perp)
        onTicker(ticker(perp, "83980", "83989"))
        await { heard.contains("fill l-1 83990") }

        market.tickers[put] = ticker(put, "100", null)
        assertThatThrownBy { adapter.place(order("p-1", put)) }.isInstanceOf(VenueRefusedException::class.java)
        adapter.close()
    }

    @Test
    fun `a held option settles at its intrinsic value from the delivery price once it expires`(
        @TempDir dir: Path,
    ) {
        market.tickers[put] = ticker(put, "900", "1000")
        val adapter = adapter(dir)
        adapter.place(order("p-1", put))
        now = expiry + 60_000

        await { heard.any { it.startsWith("settle") } }

        assertThat(heard).contains("settle $put 1000")
        assertThat(adapter.positions().rows).isEmpty()
        assertThat(adapter.account().balance).isEqualByComparingTo("10000")
        adapter.close()
    }
}

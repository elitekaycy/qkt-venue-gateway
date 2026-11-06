package com.qkt.venuegateway.paper

import com.qkt.venuegateway.adapter.AdapterContext
import com.qkt.venuegateway.adapter.NewOrder
import com.qkt.venuegateway.adapter.OrderType
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TimeInForce
import com.qkt.venuegateway.adapter.VenueRefusedException
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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PaperAdapterArmsTest {
    private val perp = "BTC_USDC-PERPETUAL"
    private var now = 1_790_000_000_000L
    private val heard = CopyOnWriteArrayList<String>()
    private var onTicker: (DeribitTicker) -> Unit = {}
    private val asked = CopyOnWriteArrayList<String>()

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

            override fun ticker(name: String): DeribitTicker {
                asked += name
                return quote(BigDecimal("84000"), BigDecimal("84000.5"))
            }

            override fun klines(
                name: String,
                minutes: Long,
                fromMs: Long,
                toMs: Long,
            ) = emptyList<DeribitKline>()

            override fun deliveryPrices(
                index: String,
                count: Int,
            ) = emptyList<Pair<LocalDate, BigDecimal>>()
        }

    private fun quote(
        bid: BigDecimal,
        ask: BigDecimal,
        mark: BigDecimal? = null,
    ) = DeribitTicker(perp, now, bid, BigDecimal.TEN, ask, BigDecimal.TEN, mark, null, null, null)

    private fun adapter(dir: Path): PaperAdapter {
        val adapter =
            PaperAdapter(
                AdapterContext(mapOf("starting_balance" to "10000", "settlement_check_ms" to "60000"), { now }, dir),
                market,
            ) { t, _ ->
                onTicker = t
                object : DeribitTickers {
                    override fun start() {}

                    override fun subscribe(names: Set<String>) {}

                    override fun close() {}
                }
            }
        adapter.connect(HeardListener(heard))
        return adapter
    }

    private fun buy(id: String) =
        NewOrder(id, perp, Side.BUY, OrderType.MARKET, BigDecimal.ONE, timeInForce = TimeInForce.GTC)

    @Test
    fun `an instrument the account does not list is refused before any price is asked for`(
        @TempDir dir: Path,
    ) {
        val adapter = adapter(dir)

        assertThatThrownBy { adapter.place(buy("a-1").copy(symbol = "DOGE_USDC-PERPETUAL")) }
            .isInstanceOf(VenueRefusedException::class.java)
            .hasMessageContaining("DOGE_USDC-PERPETUAL is not listed")
        assertThat(asked).isEmpty()
        adapter.close()
    }

    @Test
    fun `an order is priced from the live feed once the instrument has been heard, from the venue before`(
        @TempDir dir: Path,
    ) {
        val adapter = adapter(dir)

        adapter.place(buy("a-1"))
        onTicker(quote(BigDecimal("85000"), BigDecimal("85001")))
        val second = adapter.place(buy("a-2"))

        assertThat(asked).containsExactly(perp)
        assertThat(second.avgFillPrice).isEqualByComparingTo("85001")
        adapter.close()
    }

    @Test
    fun `fills are served for the range asked, and the account marks held positions by the feed's mark`(
        @TempDir dir: Path,
    ) {
        val adapter = adapter(dir)
        adapter.place(buy("a-1"))
        val at = now

        assertThat(adapter.fills(at, at).map { it.clientOrderId }).containsExactly("a-1")
        assertThat(adapter.fills(at + 1, at + 2)).isEmpty()
        assertThat(adapter.settlements(0, at + 10)).isEmpty()
        assertThat(adapter.funding(0, at + 10)).isEmpty()
        val flat = adapter.account().equity
        onTicker(quote(BigDecimal("85000"), BigDecimal("85001"), mark = BigDecimal("85500")))
        val marked = adapter.account().equity

        assertThat(marked - flat).isEqualByComparingTo("1499.5")
        adapter.close()
    }
}

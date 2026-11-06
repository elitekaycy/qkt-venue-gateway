package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.bybit.client.BybitMarketJson
import com.qkt.venuegateway.bybit.client.BybitPrint
import com.qkt.venuegateway.testkit.RecordingListener
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitAdapterArmsTest {
    @TempDir lateinit var dir: Path
    private val venues = mutableListOf<BybitVenue>()

    private fun venue(
        route: (String, Map<String, String>) -> String? = { path, query -> BybitVenue.linearRoute(path, query) },
        settings: Map<String, String> = BybitVenue.LINEAR,
    ) = BybitVenue(dir, route, settings).also { venues += it }

    @AfterEach
    fun stop() = venues.forEach { it.close() }

    @Test
    fun `what the venue pushes before a listener is connected is dropped, not kept for it`() {
        val venue = venue()
        val ticker = BybitMarketJson.ticker(BybitVenue.items("ticker-linear-BTCUSDT.json").first(), 1L)
        venue.onExecution(BybitVenue.execution("private/ws-execution-stop-fired.json"))
        venue.onExecution(BybitVenue.execution("private/ws-execution-funding.json"))
        venue.onAccountLink(true, "up")
        venue.onTicker(ticker)
        venue.onMarketLink(true, "up")

        val listener = RecordingListener()
        venue.adapter.connect(listener)

        assertThat(listener.fills).isEmpty()
        assertThat(listener.funding).isEmpty()
        assertThat(listener.quotes).isEmpty()
        assertThat(listener.connections).isEmpty()
        assertThat(listener.quoteFeed).isEmpty()
    }

    @Test
    fun `an order another client placed is not pushed to the listener`() {
        val venue = venue()
        val listener = RecordingListener()
        venue.adapter.connect(listener)

        venue.onOrder(BybitVenue.order("private/ws-order-limit-new.json").copy(orderLinkId = null))
        venue.onOrder(BybitVenue.order("private/ws-order-limit-new.json"))

        assertThat(listener.orders.map { it.clientOrderId }).containsExactly("rec-rest-1")
    }

    @Test
    fun `pushed liquidations are taped as the side of the position they closed, and served back`() {
        val venue = venue()
        venue.adapter.connect(RecordingListener())
        val from = BybitVenue.NOW - 1_000
        val print =
            BybitPrint(
                "liq-1",
                BybitVenue.NOW - 500,
                java.math.BigDecimal("86000"),
                java.math.BigDecimal("0.5"),
                "Sell",
            )

        venue.onLiquidations("BTCUSDT", listOf(print))
        val served = venue.adapter.liquidations("BTCUSDT", from, BybitVenue.NOW)

        assertThat(served.map { it.side }).containsExactly(Side.BUY)
        assertThat(served.single().timeMs).isEqualTo(print.timeMs)
        assertThat(served.single().price).isEqualByComparingTo("86000")
    }

    @Test
    fun `pushed trades are taped and served with the latest ones`() {
        val venue = venue()
        venue.adapter.connect(RecordingListener())
        val print =
            BybitPrint("t-1", BybitVenue.NOW - 500, java.math.BigDecimal("86000"), java.math.BigDecimal("0.5"), "Buy")

        venue.onTrades("BTCUSDT", listOf(print))
        val served = venue.adapter.trades("BTCUSDT", BybitVenue.NOW - 1_000, BybitVenue.NOW, 100)

        assertThat(served.map { it.id }).contains("t-1")
    }

    @Test
    fun `an account holding no hedged slot and no listed bitcoin perpetual nets`() {
        val venue =
            venue({ path, query ->
                when {
                    path != "/v5/market/instruments-info" -> BybitVenue.linearRoute(path, query)
                    "cursor" in query -> "instruments-linear-future.json"
                    else -> "instruments-linear-page-1.json"
                }
            })

        assertThat(venue.adapter.positions().accounting).isEqualTo(Accounting.NETTING)
    }

    @Test
    fun `a spot account's trade tape reads sixty recent trades, not a thousand`() {
        val venue =
            venue({ path, query ->
                when (path) {
                    "/v5/market/instruments-info" -> "instruments-spot-BTCUSDT.json"
                    "/v5/market/recent-trade" -> "recent-trade-spot.json"
                    else -> BybitVenue.linearRoute(path, query)
                }
            }, BybitVenue.SPOT)

        venue.adapter.trades("BTCUSDT", 0, BybitVenue.NOW, 10)

        assertThat(venue.param("/v5/market/recent-trade", "limit")).isEqualTo("60")
    }
}

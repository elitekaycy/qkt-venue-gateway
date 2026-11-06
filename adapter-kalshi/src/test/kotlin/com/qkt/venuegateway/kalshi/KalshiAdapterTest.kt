package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.VenueRefusedException
import com.qkt.venuegateway.kalshi.KalshiVenue.Companion.CURRY
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiAdapterTest {
    private val venue = KalshiVenue()
    private val adapter = venue.adapter()

    @AfterEach
    fun stop() {
        adapter.close()
        venue.close()
    }

    @Test
    fun `it serves bars, quotes, settlements, the tape, depth and open interest, from 1 m, 1 h and 1 d candles`() {
        assertThat(adapter.capabilities).containsExactlyInAnyOrder(
            Capability.BARS,
            Capability.QUOTES,
            Capability.SETTLEMENTS,
            Capability.TRADES,
            Capability.DEPTH,
            Capability.OPEN_INTEREST,
        )
        assertThat(adapter.klineResolutions).containsExactlyInAnyOrder(60_000L, 3_600_000L, 86_400_000L)
        assertThat(adapter.version).isNotBlank
    }

    @Test
    fun `every market of the open events is listed as an event contract, its series read by ticker when new`() {
        val listed = adapter.instruments()

        assertThat(listed).hasSize(7)
        assertThat(listed.map { it.kind }.toSet()).containsExactly(InstrumentKind.EVENT)
        val curry = listed.single { it.code == CURRY }
        assertThat(curry.category).isEqualTo("Sports")
        assertThat(curry.event!!.series).isEqualTo("KXNBA3D")
        assertThat(venue.server.requests.map { it.url.encodedPath }).contains("/trade-api/v2/series/KXNBA3D")
    }

    @Test
    fun `the account is this shard's cash, its positions held at Kalshi's portfolio value`() {
        val a = adapter.account()

        assertThat(a.currency).isEqualTo("USD")
        assertThat(a.balance).isEqualByComparingTo("999.9146")
        assertThat(a.marginUsed).isEqualByComparingTo("0.08")
        assertThat(a.equity).isEqualByComparingTo("999.9946")
        assertThat(a.marginAvailable).isEqualByComparingTo("999.9146")
        assertThat(venue.adapter(shard = 2).use { it.account().balance }).isEqualByComparingTo("0")
    }

    @Test
    fun `positions are this shard's, one net row a market`() {
        val p = adapter.positions()

        assertThat(p.accounting).isEqualTo(Accounting.NETTING)
        assertThat(p.rows.map { it.symbol }).contains(CURRY, "KXAAAGASD-26NOV06-4.0600")
        assertThat(venue.adapter(shard = 1).use { it.positions().rows }).isEmpty()
    }

    @Test
    fun `fills and settlements are read back, each fill under its order's label`() {
        val fills = adapter.fills(0L, Long.MAX_VALUE / 2)

        assertThat(fills.map { it.timeMs }).isSorted
        assertThat(fills.map { it.clientOrderId })
            .containsExactly("qkt-probe-fill", "qkt-probe-ask", "01a1ae63-1720-7260-9267-e5687417590a")
        assertThat(fills.first().side).isEqualTo(Side.BUY)
        assertThat(adapter.settlements(0L, 1_000L)).isEmpty()
    }

    @Test
    fun `bars come from the yes mid of candles, none on the demo's one-sided book, other lengths are refused`() {
        assertThat(adapter.klines(CURRY, 60_000L, 1_793_926_800_000L, 1_793_927_400_000L)).isEmpty()
        val candles = venue.server.requests.last()
        assertThat(candles.param("period_interval")).isEqualTo("1")
        assertThatThrownBy { adapter.klines(CURRY, 300_000L, 0L, 1L) }.isInstanceOf(VenueRefusedException::class.java)
        assertThatThrownBy { adapter.klines("NOPE", 60_000L, 0L, 0L) }
            .hasMessageContaining("kalshi lists no market NOPE")
    }

    @Test
    fun `the tape is Kalshi's trade history, the taker's side on each print`() {
        val prints = adapter.trades(CURRY, 1_793_926_800_000L, 1_793_930_400_000L, 10)

        assertThat(prints.single().side).isEqualTo(Side.BUY)
        assertThat(prints.single().size).isEqualByComparingTo("2")
        assertThat(adapter.trades(CURRY, 1_793_926_800_000L, 1_793_930_400_000L, 0)).isEmpty()
    }

    @Test
    fun `depth and open interest are recorded as read, at the gateway's clock`() {
        val now = System.currentTimeMillis()
        val depth = adapter.depth(CURRY, now - 60_000L, now + 60_000L)
        val oi = adapter.openInterest(CURRY, now - 60_000L, now + 60_000L)

        assertThat(depth.single().bids).isEmpty()
        val ask = depth.single().asks.single()
        assertThat(ask.price).isEqualByComparingTo("0.04")
        assertThat(oi.single().openInterest).isPositive
    }

    @Test
    fun `a quote subscription quotes listed markets at once, then from the socket's tickers, and pushes the account`() {
        adapter.connect(venue.listener)
        adapter.subscribeQuotes(setOf(CURRY, "NOPE"))

        assertThat(venue.quotes.first { it.symbol == CURRY }.ask).isEqualByComparingTo("0.04")
        val snapshot = venue.quotes.size
        venue.await { venue.links.contains(true) && venue.server.sent.size >= 3 }
        val subscribed = venue.server.sent.toList()
        assertThat(subscribed.last()).contains(CURRY).doesNotContain("NOPE")
        listOf("ticker", "fill", "user-order-executed").forEach { venue.server.push(Fixtures.text("socket/$it.json")) }
        venue.await { venue.quotes.size > snapshot && venue.fills.isNotEmpty() && venue.orders.isNotEmpty() }
        assertThat(venue.fills.single().clientOrderId).isEqualTo("qkt-probe-fill")
        assertThat(venue.orders.single().clientOrderId).isEqualTo("qkt-probe-fill")
        adapter.subscribeQuotes(setOf(CURRY))
        assertThat(venue.quotes).hasSize(snapshot + 1)
    }
}

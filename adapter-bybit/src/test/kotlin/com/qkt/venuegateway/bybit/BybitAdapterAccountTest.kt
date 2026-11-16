package com.qkt.venuegateway.bybit

import com.qkt.venuegateway.adapter.Accounting
import com.qkt.venuegateway.adapter.Capability
import com.qkt.venuegateway.adapter.CostKind
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.Side
import com.qkt.venuegateway.adapter.TradeMode
import com.qkt.venuegateway.adapter.VenueIdentity
import com.qkt.venuegateway.adapter.VenueUnsupportedException
import com.qkt.venuegateway.bybit.client.BYBIT_KLINE_INTERVALS
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BybitAdapterAccountTest {
    @TempDir lateinit var dir: Path
    private val venues = mutableListOf<BybitVenue>()
    private val day = 86_400_000L
    private val spotListing = mapOf("/v5/market/instruments-info" to "instruments-spot-BTCUSDT.json")

    private fun venue(
        settings: Map<String, String> = BybitVenue.LINEAR,
        override: Map<String, String> = emptyMap(),
    ) = BybitVenue(dir, { path, query -> override[path] ?: BybitVenue.linearRoute(path, query) }, settings).also {
        venues += it
    }

    @AfterEach
    fun stop() = venues.forEach { it.close() }

    @Test
    fun `the account is the api key on testnet, in the settle coin, with bybit's intervals`() {
        val adapter = venue().adapter

        assertThat(adapter.id).isEqualTo("bybit")
        assertThat(adapter.version).isEqualTo("dev")
        assertThat(adapter.identity()).isEqualTo(VenueIdentity("the-key", TradeMode.DEMO, "USDT"))
        assertThat(adapter.klineResolutions).isEqualTo(BYBIT_KLINE_INTERVALS.keys)
        assertThat(adapter.capabilities).contains(Capability.FUNDING, Capability.LIQUIDATIONS)
    }

    @Test
    fun `instruments are the account's trading contracts in its settle coin`() {
        val instruments = venue().adapter.instruments()

        assertThat(instruments.map { it.code }).containsExactly("BTCUSDT")
        assertThat(instruments.single().kind).isEqualTo(InstrumentKind.PERPETUAL)
    }

    @Test
    fun `the account is the unified wallet's pooled figures in usd`() {
        val account = venue().adapter.account()

        assertThat(account.currency).isEqualTo("USD")
        assertThat(account.balance).isEqualByComparingTo("19993.54")
        assertThat(account.equity).isEqualByComparingTo("19993.54")
        assertThat(account.marginUsed).isZero()
        assertThat(account.marginAvailable).isEqualByComparingTo("19993.54")
    }

    @Test
    fun `a one-way long is one netted row, the mode read from the coin's btc perpetual`() {
        val venue = venue()

        val positions = venue.adapter.positions()

        assertThat(positions.accounting).isEqualTo(Accounting.NETTING)
        val row = positions.rows.single()
        assertThat(row.symbol).isEqualTo("BTCUSDT")
        assertThat(row.quantity).isEqualByComparingTo("0.158")
        assertThat(row.avgPrice).isEqualByComparingTo("86516.68797468")
        assertThat(row.ticket).isNull()
        val reads = venue.sent("/v5/position/list")
        assertThat(reads.first().url.queryParameter("settleCoin")).isEqualTo("USDT")
        assertThat(reads.last().url.queryParameter("symbol")).isEqualTo("BTCUSDT")
    }

    @Test
    fun `an account whose btc perpetual is held by side hedges`() {
        val venue =
            BybitVenue(dir, { path, query ->
                if (path == "/v5/position/list" && "symbol" in query) {
                    "private/position-slots-hedge.json"
                } else {
                    BybitVenue.linearRoute(path, query)
                }
            }).also { venues += it }

        assertThat(venue.adapter.positions().accounting).isEqualTo(Accounting.HEDGING)
    }

    @Test
    fun `fills are the account's trade executions with their fee`() {
        val fills = venue().adapter.fills(BybitVenue.NOW - day, BybitVenue.NOW)

        val fill = fills.single()
        assertThat(fill.clientOrderId).isEqualTo("rec-stop-down")
        assertThat(fill.symbol).isEqualTo("ETHUSDT")
        assertThat(fill.side).isEqualTo(Side.SELL)
        assertThat(fill.quantity).isEqualByComparingTo("0.01")
        assertThat(fill.price).isEqualByComparingTo("2739.59")
        assertThat(fill.costs.single().kind).isEqualTo(CostKind.COMMISSION)
        assertThat(fill.costs.single().amount).isEqualByComparingTo("0.01095836")
    }

    @Test
    fun `a range longer than seven days is read seven days a call`() {
        val venue = venue()
        val from = BybitVenue.NOW - 8 * day

        venue.adapter.fills(from, BybitVenue.NOW)

        val spans =
            venue.sent("/v5/execution/list").filter { it.url.queryParameter("cursor") == null }.map {
                it.url.queryParameter("startTime")!!.toLong() to it.url.queryParameter("endTime")!!.toLong()
            }
        assertThat(spans).containsExactly(from to from + 7 * day - 1, from + 7 * day to BybitVenue.NOW)
    }

    @Test
    fun `funding is the account's funding executions, signed by the position it was paid on`() {
        val venue = venue()

        val funding = venue.adapter.funding(BybitVenue.NOW, BybitVenue.NOW + day).single()

        assertThat(funding.symbol).isEqualTo("BTCUSDT")
        assertThat(funding.amount).isEqualByComparingTo("0.0085907")
        assertThat(funding.position).isEqualByComparingTo("0.001")
        assertThat(venue.param("/v5/execution/list", "execType")).isEqualTo("Funding")
    }

    @Test
    fun `settlements are never served`() {
        assertThatThrownBy { venue().adapter.settlements(0, 1) }.isInstanceOf(VenueUnsupportedException::class.java)
    }

    @Test
    fun `a spot account declares no contract data and refuses every contract read`() {
        val adapter = venue(BybitVenue.SPOT, spotListing).adapter

        assertThat(adapter.capabilities).doesNotContain(Capability.FUNDING, Capability.OPEN_INTEREST)
        assertThatThrownBy { adapter.funding(0, 1) }.isInstanceOf(VenueUnsupportedException::class.java)
        assertThatThrownBy { adapter.fundingRates("BTCUSDT", 0, 1) }
            .isInstanceOf(VenueUnsupportedException::class.java)
        assertThatThrownBy { adapter.marks("BTCUSDT", 60_000, 0, 1) }
            .isInstanceOf(VenueUnsupportedException::class.java)
        assertThatThrownBy { adapter.openInterest("BTCUSDT", 0, 1) }
            .isInstanceOf(VenueUnsupportedException::class.java)
        assertThatThrownBy { adapter.liquidations("BTCUSDT", 0, 1) }
            .isInstanceOf(VenueUnsupportedException::class.java)
    }

    @Test
    fun `a spot account holding only its quote coin has no positions, netted, and reads no position list`() {
        val venue = venue(BybitVenue.SPOT, spotListing)

        val positions = venue.adapter.positions()

        assertThat(positions.accounting).isEqualTo(Accounting.NETTING)
        assertThat(positions.rows).isEmpty()
        assertThat(venue.sent("/v5/position/list")).isEmpty()
        assertThat(venue.param("/v5/account/wallet-balance", "accountType"))
            .isEqualTo("UNIFIED")
    }

    @Test
    fun `a spot account's reads carry no settle coin`() {
        val venue = venue(BybitVenue.SPOT)

        venue.adapter.openOrders()

        assertThat(venue.param("/v5/order/realtime", "category")).isEqualTo("spot")
        assertThat(venue.param("/v5/order/realtime", "settleCoin")).isNull()
    }
}

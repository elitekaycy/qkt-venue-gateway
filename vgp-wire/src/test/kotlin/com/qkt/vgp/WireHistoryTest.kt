package com.qkt.vgp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireHistoryTest {
    private val funding = WireFunding("fu-1", "BTC-PERPETUAL", "-0.5", "USDC", "1.5", 1_000L)
    private val rate = WireFundingRate(3_600_000L, "0.0001", "84000")
    private val print = WirePrint("t-1", 1_000L, "84000", "0.1", "buy")
    private val snapshot = WireDepthSnapshot(1_000L, listOf(listOf("99", "1")), listOf(listOf("101", "2")))
    private val point = WireOpenInterestPoint(1_000L, "123.5")

    @Test
    fun `funding reads back, its position left out when the venue does not say`() {
        val json =
            wireShape(
                funding,
                funding.copy(),
                funding.copy(fundingId = "fu-2"),
                funding.copy(symbol = "ETH-PERPETUAL"),
                funding.copy(amount = "0.5"),
                funding.copy(currency = "BTC"),
                funding.copy(position = null),
                funding.copy(time = 2_000L),
            )
        wireShape(WireFundings(listOf(funding)), WireFundings(listOf(funding.copy())), WireFundings(emptyList()))

        assertThat(json.keys).contains("funding_id")
        assertThat(written(WireFunding("f", "X", "1", "USDC", time = 5L)))
            .isEqualTo("""{"funding_id":"f","symbol":"X","amount":"1","currency":"USDC","time":5}""")
        refusesEmpty<WireFunding>()
        refusesEmpty<WireFundings>()
    }

    @Test
    fun `funding rates read back, an unpublished price and a last page's next left out`() {
        val page = WireFundingRates(listOf(rate), 7_200_000L)

        wireShape(rate, rate.copy(), rate.copy(time = 1L), rate.copy(rate = "-0.0001"), rate.copy(price = null))
        wireShape(page, page.copy(), page.copy(rates = emptyList()), page.copy(next = null))

        assertThat(written(WireFundingRate(5L, "0.1"))).isEqualTo("""{"time":5,"rate":"0.1"}""")
        assertThat(WireFundingRates(emptyList()).next).isNull()
        refusesEmpty<WireFundingRate>()
        refusesEmpty<WireFundingRates>()
    }

    @Test
    fun `trades and liquidations read back as pages of the same print`() {
        val trades = WireTrades(listOf(print), 2_000L)
        val liquidations = WireLiquidations(listOf(print), 2_000L)

        wireShape(
            print,
            print.copy(),
            print.copy(id = "t-2"),
            print.copy(time = 2L),
            print.copy(price = "1"),
            print.copy(size = "1"),
            print.copy(side = "sell"),
        )
        wireShape(trades, trades.copy(), trades.copy(trades = emptyList()), trades.copy(next = null))
        wireShape(
            liquidations,
            liquidations.copy(),
            liquidations.copy(liquidations = emptyList()),
            liquidations.copy(next = null),
        )

        assertThat(written(WireTrades(emptyList()))).isEqualTo("""{"trades":[]}""")
        assertThat(written(WireLiquidations(emptyList()))).isEqualTo("""{"liquidations":[]}""")
        refusesEmpty<WirePrint>()
        refusesEmpty<WireTrades>()
        refusesEmpty<WireLiquidations>()
    }

    @Test
    fun `depth reads back with each level a price and amount pair`() {
        val page = WireDepth(listOf(snapshot), 2_000L)

        wireShape(
            snapshot,
            snapshot.copy(),
            snapshot.copy(time = 2L),
            snapshot.copy(bids = emptyList()),
            snapshot.copy(asks = emptyList()),
        )
        wireShape(page, page.copy(), page.copy(depth = emptyList()), page.copy(next = null))

        assertThat(written(snapshot)).isEqualTo("""{"time":1000,"bids":[["99","1"]],"asks":[["101","2"]]}""")
        assertThat(WireDepth(emptyList()).next).isNull()
        refusesEmpty<WireDepthSnapshot>()
        refusesEmpty<WireDepth>()
    }

    @Test
    fun `open interest reads back under its wire name`() {
        val page = WireOpenInterest(listOf(point), 2_000L)

        wireShape(point, point.copy(), point.copy(time = 2L), point.copy(openInterest = "0"))
        val json = wireShape(page, page.copy(), page.copy(openInterest = emptyList()), page.copy(next = null))

        assertThat(json.keys).containsExactly("open_interest", "next")
        assertThat(written(point)).isEqualTo("""{"time":1000,"open_interest":"123.5"}""")
        assertThat(WireOpenInterest(emptyList()).next).isNull()
        refusesEmpty<WireOpenInterestPoint>()
        refusesEmpty<WireOpenInterest>()
    }
}

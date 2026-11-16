package com.qkt.vgp

import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WireMarketTest {
    private val bar = WireBar(60_000L, "100", "101", "99", "100.5", "2")
    private val mark = WireMark(60_000L, "100", "99.9")

    @Test
    fun `a quote reads back side by side, a side the venue did not report left out`() {
        val quote = WireQuote("BTC-1", "99.5", "100.5", "1", "2", "100", "55.5", "100.2", 1_000L, "99.8")

        val json =
            wireShape(
                quote,
                quote.copy(),
                quote.copy(symbol = "BTC-2"),
                quote.copy(bid = null),
                quote.copy(ask = null),
                quote.copy(bidSize = null),
                quote.copy(askSize = null),
                quote.copy(mark = null),
                quote.copy(markIv = null),
                quote.copy(underlying = null),
                quote.copy(time = 2_000L),
                quote.copy(index = null),
            )

        assertThat(json.keys).contains("bid_size", "ask_size", "mark_iv")
        assertThat(written(WireQuote("X", ask = "1", time = 5L))).isEqualTo("""{"symbol":"X","ask":"1","time":5}""")
        refusesEmpty<WireQuote>()
    }

    @Test
    fun `bars read back, and a last page names no next`() {
        val page = WireBars(listOf(bar), 120_000L)

        wireShape(
            bar,
            bar.copy(),
            bar.copy(start = 0L),
            bar.copy(open = "1"),
            bar.copy(high = "1"),
            bar.copy(low = "1"),
            bar.copy(close = "1"),
            bar.copy(volume = "1"),
        )
        wireShape(page, page.copy(), page.copy(bars = emptyList()), page.copy(next = null))

        assertThat(WireBars(listOf(bar)).next).isNull()
        assertThat(written(WireBars(emptyList()))).isEqualTo("""{"bars":[]}""")
        refusesEmpty<WireBar>()
        refusesEmpty<WireBars>()
    }

    @Test
    fun `marks read back, either price left out when the venue did not report it`() {
        val page = WireMarks(listOf(mark), 120_000L)

        wireShape(mark, mark.copy(), mark.copy(time = 0L), mark.copy(mark = null), mark.copy(index = null))
        wireShape(page, page.copy(), page.copy(marks = emptyList()), page.copy(next = null))

        assertThat(written(WireMark(5L))).isEqualTo("""{"time":5}""")
        assertThat(WireMarks(emptyList()).next).isNull()
        refusesEmpty<WireMark>()
        refusesEmpty<WireMarks>()
    }

    @Test
    fun `a change names only the fields it changes, and an empty body changes nothing`() {
        val change = WireChange("2", "84000", "83900")

        val json =
            wireShape(
                change,
                change.copy(),
                change.copy(quantity = null),
                change.copy(limitPrice = null),
                change.copy(stopPrice = null),
            )

        assertThat(json.keys).containsExactly("quantity", "limit_price", "stop_price")
        assertThat(Json.decodeFromString(WireChange.serializer(), "{}")).isEqualTo(WireChange())
        assertThat(written(WireChange(limitPrice = "1"))).isEqualTo("""{"limit_price":"1"}""")
    }

    @Test
    fun `a close names a symbol, a quantity, a ticket or its own id, each optional`() {
        val close = WireClose("BTC-PERPETUAL", "0.5", "t-1", "close-1")

        val json =
            wireShape(
                close,
                close.copy(),
                close.copy(symbol = null),
                close.copy(quantity = null),
                close.copy(ticket = null),
                close.copy(clientOrderId = null),
            )

        assertThat(json.keys).containsExactly("symbol", "quantity", "ticket", "client_order_id")
        assertThat(Json.decodeFromString(WireClose.serializer(), "{}")).isEqualTo(WireClose())
    }
}

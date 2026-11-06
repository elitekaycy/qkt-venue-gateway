package com.qkt.venuegateway.kalshi.client

import com.qkt.venuegateway.kalshi.Fixtures
import com.qkt.venuegateway.kalshi.param
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class KalshiPublicTest {
    private val server =
        Fixtures.Server { _, path, query, _ ->
            when (path) {
                "/series" -> "demo/series-list.json"
                "/series/KXHIGHNY" -> "public/series-KXHIGHNY.json"
                "/events" -> if (query["cursor"] == null) "public/events-page-1.json" else "public/events-page-2.json"
                "/markets/KXHIGHNY-26NOV06-B67.5" -> "public/market-KXHIGHNY-26NOV06-B67.5.json"
                "/markets" -> "demo/markets-by-tickers.json"
                "/markets/KXHIGHNY-26NOV06-B67.5/orderbook" -> "public/orderbook-KXHIGHNY-26NOV06-B67.5.json"
                "/markets/trades" -> TRADE_PAGES[query["cursor"]]
                "/series/KXHIGHNY/markets/KXHIGHNY-26NOV06-B67.5/candlesticks" ->
                    "public/candles-1m-KXHIGHNY-26NOV06-B67.5.json"
                "/series/KXHIGHNY/markets/WIDE/candlesticks" -> "!400 public/error-candles-range.json"
                else -> null
            }
        }
    private val public = KalshiPublic(KalshiRest(server.restUrl))

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `the series list and one series are read with their fee terms and cadence`() {
        assertThat(public.series().map { it.ticker }).containsExactly("HARR30276", "KXHARRISONFEE3211")
        val s = public.series("KXHIGHNY")!!
        assertThat(s.category).isEqualTo("Climate and Weather")
        assertThat(s.frequency).isEqualTo("daily")
        assertThat(s.feeType).isEqualTo("quadratic")
        assertThat(s.feeMultiplier).isEqualByComparingTo("1")
        assertThat(public.series("NOPE")).isNull()
    }

    @Test
    fun `open events are read page by page, following the cursor to its empty end`() {
        val events = public.openEvents()

        assertThat(events.map { it.eventTicker }).containsExactly("KXHIGHNY-26NOV05", "KXHIGHNY-26NOV06")
        assertThat(server.requests.map { it.url.queryParameter("cursor") })
            .containsExactly(null, "CgYIwOO41wYSEEtYSElHSE5ZLTI2Tk9WMDU")
        assertThat(server.requests.first().param("with_nested_markets")).isEqualTo("true")
        assertThat(events[1].markets).hasSize(6)
    }

    @Test
    fun `a market is read exactly, an unknown one is null, and several are read in one call`() {
        val m = public.market("KXHIGHNY-26NOV06-B67.5")!!
        assertThat(m.status).isEqualTo("active")
        assertThat(m.strikeType).isEqualTo("between")
        assertThat(m.priceRanges.single().step).isEqualByComparingTo("0.01")
        assertThat(public.market("NOPE")).isNull()
        val both = public.markets(listOf("KXNBA3D-26NOV05MIAGSW-GSWSCURRY30", "KXAAAGASD-26NOV06-4.0600"))
        assertThat(both.map { it.ticker }).containsExactlyInAnyOrder(
            "KXNBA3D-26NOV05MIAGSW-GSWSCURRY30",
            "KXAAAGASD-26NOV06-4.0600",
        )
        assertThat(server.requests.last().param("tickers")).contains(",")
    }

    @Test
    fun `a book is its yes bids and no bids, ascending as Kalshi sends them`() {
        val book = public.book("KXHIGHNY-26NOV06-B67.5", 10)

        assertThat(book.yes).isNotEmpty
        assertThat(book.no).isNotEmpty
        assertThat(book.yes.map { it.first }).isSorted
        assertThat(server.requests.single().param("depth")).isEqualTo("10")
    }

    @Test
    fun `trades are read across pages in whole seconds`() {
        val trades = public.trades("KXHIGHNY-26NOV06-B67.5", 1_793_923_275_000L, 1_793_926_875_999L)

        assertThat(trades).hasSize(51)
        assertThat(trades.map { it.timeMs }).isSorted
        assertThat(server.requests.first().param("min_ts")).isEqualTo("1793923275")
        assertThat(server.requests.first().param("max_ts")).isEqualTo("1793926875")
        assertThat(trades.map { it.takerBookSide }.toSet()).isSubsetOf("bid", "ask")
    }

    @Test
    fun `candles carry the yes bid and ask in dollars, and a range over 5000 is Kalshi's refusal`() {
        val candles = public.candles("KXHIGHNY", "KXHIGHNY-26NOV06-B67.5", 1, 0L, 1_000L)
        assertThat(candles).isNotEmpty
        assertThat(candles.map { it.endMs }).isSorted
        assertThat(candles.first().yesAsk.close).isGreaterThan(BigDecimal.ZERO)
        assertThatThrownBy { public.candles("KXHIGHNY", "WIDE", 1, 0L, 1_000L) }
            .isInstanceOf(KalshiException::class.java)
            .hasMessageContaining("400 bad_request")
            .hasMessageContaining("max candlesticks: 5000")
    }

    private companion object {
        val TRADE_PAGES =
            mapOf(
                null to "public/trades-page-1.json",
                "EhIKEAcnFzSweYYJb-FNQII9yhsaDAjUy7TXBhDA86XGAw" to "public/trades-page-2.json",
                "EhIKEAcnFzD2EamB0v7gBcVXAaYaDAjnyrTXBhCQr8idAg" to "public/trades-page-3.json",
            )
    }
}

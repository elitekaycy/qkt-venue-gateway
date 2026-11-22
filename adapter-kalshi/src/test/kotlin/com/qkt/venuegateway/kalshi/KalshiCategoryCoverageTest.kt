package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.kalshi.client.KalshiEvent
import com.qkt.venuegateway.kalshi.client.KalshiMarketJson
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiRest
import com.qkt.venuegateway.kalshi.client.KalshiSeries
import com.qkt.venuegateway.support.JsonReads.objects
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Every category of Kalshi's catalogue through the adapter's mapping. Reads production's public market data (no key,
 * nothing is written or traded), only with `KALSHI_PUBLIC_LIVE=1`: it takes the whole series list, then from each
 * category a few series that have open events, and maps every market of those events as the listing does. A category
 * with nothing open is read through a few of its settled events instead (their markets map `disabled`, with the terms
 * and outcome Kalshi recorded). Each market must map to a well-formed `event` instrument ([KalshiInstrumentRules]),
 * apart from the recorded market whose floor is above its cap, which the listing leaves out. The table it measured is
 * written to `build/kalshi-category-coverage.md`.
 */
@Timeout(value = 25, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KalshiCategoryCoverageTest {
    @BeforeEach
    fun enabled() =
        assumeTrue(System.getenv("KALSHI_PUBLIC_LIVE") == "1", "set KALSHI_PUBLIC_LIVE=1 to read production")

    private class Seen(
        val category: String,
        val seriesCount: Int,
    ) {
        val sampled = mutableListOf<String>()
        val settled = mutableListOf<String>()
        var events = 0
        var markets = 0
        var settledMarkets = 0
        var left = 0
        var tradable = 0
        val strikes = sortedSetOf<String>()
        val fees = sortedSetOf<String>()
        val shards = sortedSetOf<Int>()
    }

    @Test
    fun `every category maps to well-formed event instruments`() {
        val rest = KalshiRest(KalshiEnvironment.PRODUCTION.restUrl)
        val public = KalshiPublic(rest)
        val byCategory = public.series().groupBy { it.category ?: NO_CATEGORY }.toSortedMap()
        val seen = byCategory.map { (category, series) -> sample(rest, public, category, series) }

        Files.writeString(Path.of("build", "kalshi-category-coverage.md"), table(seen))
        assertThat(seen.size).describedAs("categories").isGreaterThanOrEqualTo(MIN_CATEGORIES)
        for (word in NAMED) {
            val category = seen.firstOrNull { word.matches(it.category) }
            assertThat(category).describedAs("a category named $word").isNotNull
            assertThat(category!!.markets).describedAs("markets mapped in ${category.category}").isPositive
        }
        seen.filter { it.sampled.isNotEmpty() }.forEach {
            assertThat(it.markets).describedAs("markets mapped in ${it.category}").isPositive
        }
        seen.filter { it.settled.isNotEmpty() }.forEach {
            assertThat(it.settledMarkets).describedAs("settled markets mapped in ${it.category}").isPositive
        }
    }

    private fun sample(
        rest: KalshiRest,
        public: KalshiPublic,
        category: String,
        series: List<KalshiSeries>,
    ): Seen {
        val seen = Seen(category, series.size)
        val ordered = series.sortedBy { it.ticker }
        val stride = (ordered.size / CANDIDATES).coerceAtLeast(1)
        val spread = ordered.filterIndexed { i, _ -> i % stride == 0 }.take(CANDIDATES)
        // a category the spread finds nothing open in is read series by series until it yields, or ends
        for (candidate in spread + (ordered - spread.toSet())) {
            if (seen.sampled.size == SERIES_PER_CATEGORY) break
            val events = KalshiLiveReads.paced { public.openEvents(candidate.ticker) }
            if (events.isEmpty()) continue
            seen.sampled += candidate.ticker
            seen.events += events.size
            events.forEach { map(seen, it, candidate, settled = false) }
        }
        if (seen.sampled.isEmpty() && ordered.all { it.ticker.startsWith(KalshiTerms.COMBO_PREFIX) }) {
            sampleCombos(rest, seen, ordered)
        }
        if (seen.sampled.isEmpty()) sampleSettled(rest, seen, ordered)
        return seen
    }

    /** The combo category: open combo markets, each under the longest of [ordered]'s series its event belongs to. */
    private fun sampleCombos(
        rest: KalshiRest,
        seen: Seen,
        ordered: List<KalshiSeries>,
    ) {
        for (market in KalshiLiveReads.combos(rest, COMBOS)) {
            val series =
                ordered.filter { market.eventTicker.startsWith("${it.ticker}-") }.maxByOrNull { it.ticker.length }
                    ?: continue
            if (series.ticker !in seen.sampled) seen.sampled += series.ticker
            seen.events++
            map(seen, KalshiEvent(market.eventTicker, series.ticker, false, null, null, listOf(market)), series, false)
        }
    }

    /** A category with nothing open: a few series' settled events, which Kalshi keeps with their markets. */
    private fun sampleSettled(
        rest: KalshiRest,
        seen: Seen,
        ordered: List<KalshiSeries>,
    ) {
        val params = listOf("status" to "settled", "with_nested_markets" to "true", "limit" to "$SETTLED_EVENTS")
        for (candidate in ordered) {
            if (seen.settled.size == SERIES_PER_CATEGORY) break
            val page = KalshiLiveReads.paced { rest.get("/events", params + ("series_ticker" to candidate.ticker)) }
            val events = page.objects("events").map(KalshiMarketJson::event).map { KalshiLiveReads.archived(rest, it) }
            if (events.none { it.markets.isNotEmpty() }) continue
            seen.settled += candidate.ticker
            events.forEach { map(seen, it, candidate, settled = true) }
        }
    }

    private fun map(
        seen: Seen,
        event: KalshiEvent,
        series: KalshiSeries,
        settled: Boolean,
    ) {
        for (market in event.markets) {
            val shard = market.exchangeIndex ?: 0
            val instrument =
                runCatching { KalshiMapping.instrument(market, event, series, shard) }
                    .getOrElse {
                        assertThat(it).isInstanceOf(IllegalStateException::class.java)
                        assertThat(it.message).describedAs("${market.ticker} refused").contains("is between")
                        seen.left++
                        null
                    } ?: continue
            KalshiInstrumentRules.wellFormed(instrument, market.ticker, series.category)
            seen.shards += shard
            seen.strikes += instrument.event!!.strikeType.name
            instrument.event!!.fee?.let { seen.fees += it.model.name }
            if (settled) {
                seen.settledMarkets++
            } else {
                seen.markets++
                if (instrument.trading.name == "FULL") seen.tradable++
            }
        }
    }

    private fun table(seen: List<Seen>): String =
        buildString {
            appendLine("| ${COLUMNS.joinToString(" | ")} |")
            appendLine("|${COLUMNS.joinToString("|") { "---" }}|")
            for (s in seen) {
                val cells =
                    listOf(
                        s.category,
                        s.seriesCount,
                        s.sampled.joinToString(" "),
                        s.events,
                        s.markets,
                        s.tradable,
                        s.settled.joinToString(" "),
                        s.settledMarkets,
                        s.left,
                        s.strikes.joinToString(", "),
                        s.fees.joinToString(", "),
                        s.shards.joinToString(", "),
                    )
                appendLine("| ${cells.joinToString(" | ")} |")
            }
        }

    private companion object {
        const val NO_CATEGORY = "(none)"
        const val MIN_CATEGORIES = 8
        const val SERIES_PER_CATEGORY = 3
        const val CANDIDATES = 30
        const val SETTLED_EVENTS = 50
        const val COMBOS = 50
        val COLUMNS =
            listOf(
                "category",
                "series",
                "sampled",
                "events",
                "markets",
                "tradable",
                "settled sample",
                "settled markets",
                "left out",
                "strike types",
                "fee models",
                "shards",
            )
        val NAMED =
            listOf(
                "sports",
                "politics|elections",
                "crypto",
                "economics|financials",
                "climate.*|weather",
                "entertainment",
            ).map { Regex(it, RegexOption.IGNORE_CASE) }
    }
}

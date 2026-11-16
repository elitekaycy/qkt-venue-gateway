package com.qkt.venuegateway.kalshi

import com.qkt.venuegateway.adapter.Instrument
import com.qkt.venuegateway.adapter.InstrumentKind
import com.qkt.venuegateway.adapter.StrikeType
import com.qkt.venuegateway.kalshi.client.KalshiEvent
import com.qkt.venuegateway.kalshi.client.KalshiException
import com.qkt.venuegateway.kalshi.client.KalshiPublic
import com.qkt.venuegateway.kalshi.client.KalshiRest
import com.qkt.venuegateway.kalshi.client.KalshiSeries
import java.math.BigDecimal
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
 * category a few series that have open events, and maps every market of those events as the listing does. Each must
 * map to a well-formed `event` instrument (the rules of the contract kit's event checks), apart from the recorded
 * market whose floor is above its cap, which the listing leaves out. The table it measured is written to
 * `build/kalshi-category-coverage.md`.
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
        var events = 0
        var markets = 0
        var left = 0
        var tradable = 0
        val strikes = sortedSetOf<String>()
        val fees = sortedSetOf<String>()
        val frequencies = sortedSetOf<String>()
        val shards = sortedSetOf<Int>()
    }

    @Test
    fun `every category maps to well-formed event instruments`() {
        val public = KalshiPublic(KalshiRest(KalshiEnvironment.PRODUCTION.restUrl))
        val byCategory = public.series().groupBy { it.category ?: NO_CATEGORY }.toSortedMap()
        val seen = byCategory.map { (category, series) -> sample(public, category, series) }

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
    }

    private fun sample(
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
            val events = openEvents(public, candidate.ticker)
            if (events.isEmpty()) continue
            seen.sampled += candidate.ticker
            seen.events += events.size
            for (event in events) {
                for (market in event.markets) {
                    val shard = market.exchangeIndex ?: 0
                    val instrument =
                        runCatching { KalshiMapping.instrument(market, event, candidate, shard) }
                            .getOrElse {
                                assertThat(it).isInstanceOf(IllegalStateException::class.java)
                                assertThat(it.message).describedAs("${market.ticker} refused").contains("is between")
                                seen.left++
                                null
                            } ?: continue
                    wellFormed(instrument, market.ticker, candidate.category)
                    seen.markets++
                    seen.shards += shard
                    seen.strikes += instrument.event!!.strikeType.name
                    instrument.event!!.fee?.let { seen.fees += it.model.name }
                    instrument.event!!.frequency?.let { seen.frequencies += it }
                    if (instrument.trading.name == "FULL") seen.tradable++
                }
            }
        }
        return seen
    }

    /** One series' open events, paced to stay under Kalshi's request limit; a 429 backs off and tries again. */
    private fun openEvents(
        public: KalshiPublic,
        ticker: String,
    ): List<KalshiEvent> {
        var wait = PAUSE_MS
        repeat(RETRIES) {
            Thread.sleep(wait)
            try {
                return public.openEvents(ticker)
            } catch (e: KalshiException) {
                if (e.status != TOO_MANY_REQUESTS) throw e
                wait *= BACKOFF
            }
        }
        return public.openEvents(ticker)
    }

    private fun wellFormed(
        i: Instrument,
        ticker: String,
        category: String?,
    ) {
        val what = "instrument $ticker"
        assertThat(i.code).describedAs("$what code").isEqualTo(ticker)
        assertThat(i.kind).describedAs("$what kind").isEqualTo(InstrumentKind.EVENT)
        assertThat(i.category).describedAs("$what category").isEqualTo(category)
        assertThat(i.tickSize).describedAs("$what tick").isPositive
        i.tickRanges?.forEach { assertThat(it.tick).describedAs("$what tick range tick").isPositive }
        val e = i.event!!
        assertThat(e.series).describedAs("$what series").isNotBlank
        assertThat(e.event).describedAs("$what event").isNotBlank
        assertThat(e.closeAtMs).describedAs("$what close").isGreaterThanOrEqualTo(e.openAtMs)
        val bounds = (e.floor != null) to (e.cap != null)
        val expected =
            when (e.strikeType) {
                StrikeType.BETWEEN -> true to true
                StrikeType.GREATER, StrikeType.GREATER_OR_EQUAL -> true to false
                StrikeType.LESS, StrikeType.LESS_OR_EQUAL -> false to true
                StrikeType.CUSTOM, StrikeType.NONE -> false to false
            }
        assertThat(bounds).describedAs("$what (floor, cap) present for ${e.strikeType}").isEqualTo(expected)
        e.outcome?.let { assertThat(it).describedAs("$what outcome").isBetween(BigDecimal.ZERO, BigDecimal.ONE) }
        e.fee?.let { fee ->
            listOfNotNull(fee.takerRate, fee.makerRate).forEach {
                assertThat(it)
                    .describedAs("$what fee rate")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                    .isLessThan(BigDecimal.ONE)
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
        const val PAUSE_MS = 300L
        const val RETRIES = 4
        const val BACKOFF = 4
        const val TOO_MANY_REQUESTS = 429
        val COLUMNS =
            listOf(
                "category",
                "series",
                "sampled",
                "events",
                "markets",
                "tradable",
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

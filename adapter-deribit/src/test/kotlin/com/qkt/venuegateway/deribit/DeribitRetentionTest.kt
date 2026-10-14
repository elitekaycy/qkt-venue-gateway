package com.qkt.venuegateway.deribit

import com.qkt.venuegateway.deribit.client.DeribitPrivateJson
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** How long the gateway keeps its own recordings of depth and open interest, and how it lets go of them. */
class DeribitRetentionTest {
    private var now = noon("2026-10-05")
    private val unreachable: (String, Int) -> Nothing = { _, _ -> error("the book is not read in the past") }

    private fun order() =
        DeribitPrivateJson.order(
            Json
                .parseToJsonElement(javaClass.getResource("/fixtures/private/buy-limit-open.json")!!.readText())
                .jsonObject["response"]!!
                .jsonObject["result"]!!
                .jsonObject["order"]!!
                .jsonObject,
        )

    private fun noon(day: String) =
        LocalDate
            .parse(day)
            .atTime(12, 0)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    private fun depth(
        dir: Path,
        days: Int,
    ) = DeribitDepth(unreachable, dir, { now }, days)

    private fun openInterest(
        dir: Path,
        days: Int,
    ) = DeribitOpenInterest({ error("the ticker is not read in the past") }, dir, { now }, days)

    private fun recordDay(
        dir: Path,
        kind: String,
        code: String,
        day: String,
    ) {
        val file = dir.resolve("$kind/$code/$day.csv")
        Files.createDirectories(file.parent)
        val row = if (kind == "depth") "${noon(day)},86470:1,86471:2" else "${noon(day)},1470"
        Files.writeString(file, (if (kind == "depth") "time,bids,asks\n" else "time,open_interest\n") + "$row\n")
    }

    private fun days(
        dir: Path,
        kind: String,
        code: String,
    ) = Files.list(dir.resolve("$kind/$code")).use { f -> f.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `retention is 0 unless set, whole days are read, and anything else is refused naming the setting`() {
        assertThat(DeribitRetention.of(emptyMap())).isEqualTo(DeribitRetention(0, 0))
        assertThat(DeribitSettings.of(mapOf("environment" to "testnet")).retention).isEqualTo(DeribitRetention(0, 0))
        val set =
            mapOf(
                "environment" to "testnet",
                "depth_retention_days" to "30",
                "open_interest_retention_days" to "7",
            )
        assertThat(DeribitSettings.of(set).retention).isEqualTo(DeribitRetention(30, 7))
        listOf("-1", "1.5", "thirty", "+3", " 3").forEach { bad ->
            assertThatThrownBy { DeribitRetention.of(mapOf("depth_retention_days" to bad)) }
                .hasMessageContaining("setting depth_retention_days")
                .hasMessageContaining(bad)
            assertThatThrownBy {
                DeribitSettings.of(
                    mapOf(
                        "environment" to "testnet",
                        "open_interest_retention_days" to bad,
                    ),
                )
            }.hasMessageContaining("setting open_interest_retention_days")
        }
    }

    @Test
    fun `day files older than the window are deleted for every contract and the newer ones kept`(
        @TempDir dir: Path,
    ) {
        listOf("depth", "open-interest").forEach { kind ->
            listOf("2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05").forEach {
                recordDay(dir, kind, "BTC_USDC-PERPETUAL", it)
            }
            recordDay(dir, kind, "ETH_USDC-PERPETUAL", "2026-09-01")
        }

        assertThat(depth(dir, 2).prune()).isEqualTo(2)
        assertThat(openInterest(dir, 2).prune()).isEqualTo(2)

        listOf("depth", "open-interest").forEach { kind ->
            assertThat(
                days(dir, kind, "BTC_USDC-PERPETUAL"),
            ).containsExactly("2026-10-03.csv", "2026-10-04.csv", "2026-10-05.csv")
            assertThat(days(dir, kind, "ETH_USDC-PERPETUAL")).isEmpty()
        }
    }

    @Test
    fun `a retention of 0 keeps everything`(
        @TempDir dir: Path,
    ) {
        recordDay(dir, "depth", "BTC_USDC-PERPETUAL", "2020-01-01")
        recordDay(dir, "open-interest", "BTC_USDC-PERPETUAL", "2020-01-01")

        assertThat(depth(dir, 0).prune()).isZero
        assertThat(openInterest(dir, 0).prune()).isZero
        assertThat(days(dir, "depth", "BTC_USDC-PERPETUAL")).containsExactly("2020-01-01.csv")
        assertThat(days(dir, "open-interest", "BTC_USDC-PERPETUAL")).containsExactly("2020-01-01.csv")
    }

    @Test
    fun `pruning runs at most once a UTC day, the first time always`(
        @TempDir dir: Path,
    ) {
        val series = depth(dir, 1)
        recordDay(dir, "depth", "BTC_USDC-PERPETUAL", "2026-10-03")
        assertThat(series.prune()).isEqualTo(1)

        recordDay(dir, "depth", "BTC_USDC-PERPETUAL", "2026-10-03")
        now += 11 * 3_600_000L
        assertThat(series.prune()).isZero
        now += 3_600_000L
        assertThat(series.prune()).isEqualTo(1)
        assertThat(days(dir, "depth", "BTC_USDC-PERPETUAL")).isEmpty()
    }

    @Test
    fun `a read of a pruned range answers what remains and never fails`(
        @TempDir dir: Path,
    ) {
        listOf("2026-10-01", "2026-10-04").forEach {
            recordDay(dir, "depth", "BTC_USDC-PERPETUAL", it)
            recordDay(dir, "open-interest", "BTC_USDC-PERPETUAL", it)
        }
        val books = depth(dir, 1).also { it.prune() }
        val figures = openInterest(dir, 1).also { it.prune() }
        val from = noon("2026-09-30")
        val to = noon("2026-10-04")

        assertThat(books.read("BTC_USDC-PERPETUAL", from, to).map { it.timeMs }).containsExactly(to)
        assertThat(figures.read("BTC_USDC-PERPETUAL", from, to).map { it.timeMs }).containsExactly(to)
        assertThat(books.read("BTC_USDC-PERPETUAL", from, noon("2026-10-02"))).isEmpty()
        assertThat(figures.read("BTC_USDC-PERPETUAL", from, noon("2026-10-02"))).isEmpty()
    }

    @Test
    fun `the adapter prunes when it connects`(
        @TempDir dir: Path,
    ) {
        recordDay(dir, "depth", "BTC_USDC-PERPETUAL", "1969-12-30")
        recordDay(dir, "depth", "BTC_USDC-PERPETUAL", "1970-01-01")
        recordDay(dir, "open-interest", "BTC_USDC-PERPETUAL", "1969-12-30")
        val settings = mapOf("depth_retention_days" to "1", "open_interest_retention_days" to "1")
        val (adapter, _) = ScriptedDeribit(order()).adapter(dir, unusedMarket(), settings = settings)

        val deadline = System.currentTimeMillis() + 5_000
        while (days(dir, "open-interest", "BTC_USDC-PERPETUAL").isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        adapter.close()

        assertThat(days(dir, "depth", "BTC_USDC-PERPETUAL")).containsExactly("1970-01-01.csv")
        assertThat(days(dir, "open-interest", "BTC_USDC-PERPETUAL")).isEmpty()
    }
}
